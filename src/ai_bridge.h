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
inline action_id ai_bridge_action( avatar &u, map &here )
{
    const std::string base = PATH_INFO::savedir().get_unrelative_path().string();
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
