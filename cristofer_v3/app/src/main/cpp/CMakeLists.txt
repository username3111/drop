cmake_minimum_required(VERSION 3.18.1)

project("securelogic")

add_library(
        securelogic
        SHARED
        native-lib.cpp
        base64.cpp)

find_library(
        log-lib
        log)

target_link_libraries(
        securelogic
        ${log-lib})