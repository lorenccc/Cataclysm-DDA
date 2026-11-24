#pragma once
#include <fstream>
#include <cstdio>
#include <string>
#include "action.h"
#include "avatar.h"
#include "map.h"
#include "cata_path.h"
#include "path_info.h"

// Experimental local bridge. No network or credentials in the engine.
// A controller writes one command atomically; absent commands leave manual input intact.
inline void ai_bridge_observe( avatar &u, map &here )
{
    const std::string base = PATH_INFO::savedir();
    // Publish only terrain the avatar can actually see.
    const auto pos = u.pos_bub();
    std::ofstream state( base + "/ai_state.tmp", std::ios::trunc );
    if( state ) {
        state << "Hunger: " << u.get_hunger() << "\nThirst: " << u.get_thirst()
              << "\nPain: " << u.get_perceived_pain() << "\n";
        state << "Visible terrain (relative x,y; movement cost):\n";
        for( int dy = -5; dy <= 5; ++dy ) {
            for( int dx = -5; dx <= 5; ++dx ) {
                const auto p = pos + tripoint_rel_ms( dx, dy, 0 );
                if( here.inbounds( p ) && u.sees( here, p ) ) {
                    state << dx << "," << dy << ": " << here.tername( p )
                          << "; " << here.furnname( p ) << "; " << here.move_cost( p ) << "\n";
                }
            }
        }
        state.close();
        std::rename( ( base + "/ai_state.tmp" ).c_str(), ( base + "/ai_state.txt" ).c_str() );
    }
}

inline action_id ai_bridge_action()
{
    const std::string base = PATH_INFO::savedir();
    std::ifstream command( base + "/ai_command.txt" );
    std::string token;
    if( !( command >> token ) ) {
        return ACTION_NULL;
    }
    command.close();
    std::remove( ( base + "/ai_command.txt" ).c_str() );
    if( token == "N" ) return ACTION_MOVE_FORTH;
    if( token == "NE" ) return ACTION_MOVE_FORTH_RIGHT;
    if( token == "E" ) return ACTION_MOVE_RIGHT;
    if( token == "SE" ) return ACTION_MOVE_BACK_RIGHT;
    if( token == "S" ) return ACTION_MOVE_BACK;
    if( token == "SW" ) return ACTION_MOVE_BACK_LEFT;
    if( token == "W" ) return ACTION_MOVE_LEFT;
    if( token == "NW" ) return ACTION_MOVE_FORTH_LEFT;
    if( token == "WAIT" ) return ACTION_PAUSE;
    return ACTION_NULL;
}
