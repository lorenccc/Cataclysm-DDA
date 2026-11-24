#pragma once
#ifndef CATA_SRC_AI_BRIDGE_H
#define CATA_SRC_AI_BRIDGE_H

#include <string>
class avatar;
class map;

// A ticket is valid only within one call to get_player_input.
bool ai_poll( avatar &who, map &here, int ticket, std::string &action );
#endif
