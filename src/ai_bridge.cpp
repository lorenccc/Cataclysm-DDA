#include "ai_bridge.h"

#if defined(__ANDROID__)
#include <jni.h>
#include <SDL3/SDL_system.h>
#include <sstream>
#include <vector>
#include "action.h"
#include "activity_actor_definitions.h"
#include "avatar.h"
#include "bodypart.h"
#include "calendar.h"
#include "item.h"
#include "item_location.h"
#include "level_cache.h"
#include "map.h"
#include "map_selector.h"
#include "messages.h"
#include "weather.h"
#include "worldfactory.h"

namespace
{
struct observation {
    std::vector<item_location> pickup;
    std::vector<item_location> food;
    std::vector<item_location> wearable;
};

std::string describe( avatar &u, map &here, observation &targets )
{
    targets = {};
    std::ostringstream out;
    const auto pos = u.pos_bub( here );
    out << "time " << to_string( calendar::turn ) << " weather " << get_weather().weather_id.str()
        << "\nposition " << u.pos_abs().x() << ',' << u.pos_abs().y() << ',' << pos.z()
        << "\nhunger " << u.get_hunger() << " thirst " << u.get_thirst() << " pain "
        << u.get_perceived_pain() << " sleepiness " << u.get_sleepiness() << "\nhp ";
    static const efftype_id bleeding( "bleed" );
    for( const bodypart_id &bp : u.get_all_body_parts() ) {
        out << bp.id().str() << '=' << u.get_part_hp_cur( bp ) << '/' << u.get_part_hp_max( bp );
        if( int severity = u.get_effect_int( bleeding, bp.id() ) ) {
            out << " bleeding=" << severity;
        }
        out << ' ';
    }
    out << "\nVisible cells dx,dy terrain/furniture cost (north=-y):\n";
    const auto &view = here.get_cache_ref( pos.z() ).visibility_cache;
    const auto &visibility = here.get_visibility_variables_cache();
    for( int dy = -5; dy <= 5; ++dy ) {
        for( int dx = -5; dx <= 5; ++dx ) {
            const auto p = pos + tripoint_rel_ms( dx, dy, 0 );
            if( !here.inbounds( p ) || !u.sees( here, p ) ||
                here.get_visibility( view[p.x()][p.y()], visibility ) != visibility_type::CLEAR ) {
                continue;
            }
            out << dx << ',' << dy << ' ' << here.tername( p ) << '/' << here.furnname( p )
                << ' ' << here.move_cost( p );
            if( std::abs( dx ) <= 1 && std::abs( dy ) <= 1 && ( dx || dy ) ) {
                const int direction = ( dy + 1 ) * 3 + dx + 1;
                const bool inside = !here.is_outside( pos );
                if( here.open_door( u, p, inside, true ) ) {
                    out << " open=" << direction;
                }
                if( !u.is_mounted() && here.close_door( p, inside, true ) ) {
                    out << " close=" << direction;
                }
            }
            if( here.sees_some_items( p, u ) ) {
                int count = 0;
                for( item &it : here.i_at( p ) ) {
                    if( ++count > 3 ) {
                        break;
                    }
                    out << " [" << it.tname();
                    if( std::abs( dx ) <= 1 && std::abs( dy ) <= 1 && it.is_owned_by( u, true ) && !it.made_of( phase_id::LIQUID ) ) {
                        out << " pickup=" << targets.pickup.size();
                        targets.pickup.emplace_back( map_cursor( p ), &it );
                    }
                    out << ']';
                }
            }
            out << '\n';
        }
    }
    out << "Creatures:\n";
    for( const Creature *c : u.get_visible_creatures( 30 ) ) {
        const auto delta = c->pos_bub( here ) - pos;
        out << c->disp_name() << ' ' << Creature::attitude_raw_string( c->attitude_to( u ) ) << ' ' << delta.x() << ',' << delta.y() << ',' << delta.z() << '\n';
    }
    out << "Inventory:\n";
    int count = 0;
    for( item *it : u.items_with( []( const item & ) { return true; } ) ) {
        if( ++count > 60 ) {
            break;
        }
        out << it->tname();
        if( u.is_worn( *it ) ) {
            out << " worn";
        }
        if( u.get_wielded_item().get_item() == it ) {
            out << " wielded";
        }
        if( it->is_armor() && !u.is_worn( *it ) && u.can_wear( *it ).success() ) {
            out << " wear=" << targets.wearable.size();
            targets.wearable.emplace_back( u, it );
        }
        if( it->is_food() && !it->is_medication() && u.will_eat( *it ).success() ) {
            out << " consume=" << targets.food.size();
            targets.food.emplace_back( u, it );
        }
        out << '\n';
    }
    out << "Recent game messages:\n";
    for( const auto &m : Messages::recent_messages( 5 ) ) {
        out << m.second << '\n';
    }
    return out.str();
}

jbyteArray bytes( JNIEnv *env, const std::string &value )
{
    auto data = env->NewByteArray( value.size() );
    if( data ) {
        env->SetByteArrayRegion( data, 0, value.size(), reinterpret_cast<const jbyte *>( value.data() ) );
    }
    return data;
}
} // namespace

bool ai_poll( avatar &u, map &here, int ticket, std::string &action )
{
    auto *env = static_cast<JNIEnv *>( SDL_GetAndroidJNIEnv() );
    if( !env || env->PushLocalFrame( 8 ) < 0 ) {
        return false;
    }
    struct local_frame {
        JNIEnv *env;
        ~local_frame() {
            if( env->ExceptionCheck() ) {
                env->ExceptionClear();
            }
            env->PopLocalFrame( nullptr );
        }
    } frame{ env };
    jobject activity = static_cast<jobject>( SDL_GetAndroidActivity() );
    if( !activity ) {
        return false;
    }
    jclass cls = env->GetObjectClass( activity );
    static jmethodID poll = env->GetMethodID( cls, "aiPoll", "(I)I" );
    static jmethodID observe = env->GetMethodID( cls, "aiObserve", "(I[B[B)V" );
    if( !poll || !observe ) {
        return false;
    }
    const int cmd = env->CallIntMethod( activity, poll, ticket );
    static observation targets;
    if( cmd == -1 ) {
        const auto session = world_generator->active_world->world_name + "\n" + u.get_save_id();
        auto state = bytes( env, describe( u, here, targets ) );
        auto id = bytes( env, session );
        env->CallVoidMethod( activity, observe, ticket, id, state );
    } else if( cmd == 1 ) {
        action = action_ident( ACTION_PAUSE );
        return true;
    } else if( cmd >= 2 && cmd <= 10 && cmd != 6 ) {
        int cell = cmd - 2;
        action = action_ident( get_movement_action_from_delta(
                                   tripoint_rel_ms( cell % 3 - 1, cell / 3 - 1, 0 ), iso_rotate::yes ) );
        return true;
    } else if( cmd == 11 ) {
        action = "AI_SLEEP";
        return true;
    } else if( ( cmd >= 20 && cmd <= 28 ) || ( cmd >= 30 && cmd <= 38 ) ) {
        const int cell = cmd % 10;
        const auto target = u.pos_bub( here ) + tripoint_rel_ms( cell % 3 - 1, cell / 3 - 1, 0 );
        if( cell != 4 && u.sees( here, target ) ) {
            const bool inside = !here.is_outside( u.pos_bub( here ) );
            if( cmd < 30 && here.open_door( u, target, inside, true ) ) {
                u.assign_activity( open_tile_activity_actor( target ) );
            } else if( cmd >= 30 && !u.is_mounted() && here.close_door( target, inside, true ) ) {
                u.assign_activity( close_tile_activity_actor( target ) );
            } else {
                return false;
            }
            action = "AI_ACTIVITY";
            return true;
        }
    } else if( cmd >= 300 && cmd < 400 ) {
        const size_t i = cmd - 300;
        if( i < targets.wearable.size() && targets.wearable[i] && u.can_wear( *targets.wearable[i] ).success() ) {
            u.assign_activity( wear_activity_actor( { targets.wearable[i] }, { 1 } ) );
            action = "AI_ACTIVITY";
            return true;
        }
    } else if( cmd >= 100 && cmd < 200 ) {
        const size_t i = cmd - 100;
        if( i < targets.pickup.size() && targets.pickup[i] ) {
            u.assign_activity( pickup_activity_actor( { targets.pickup[i] }, { 0 }, u.pos_bub( here ), true ) );
            action = "AI_ACTIVITY";
            return true;
        }
    } else if( cmd >= 200 && cmd < 300 ) {
        const size_t i = cmd - 200;
        if( i < targets.food.size() && targets.food[i] && u.will_eat( *targets.food[i] ).success() ) {
            u.assign_activity( consume_activity_actor( targets.food[i] ) );
            action = "AI_ACTIVITY";
            return true;
        }
    }
    return false;
}
#else
bool ai_poll( avatar &, map &, int, std::string & )
{
    return false;
}
#endif
