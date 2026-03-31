cmake_minimum_required(VERSION 3.22.1)
project("airplay_crypto")

add_library(
    airplay_crypto
    SHARED
    airplay_jni.c
    playfair/playfair.c
    playfair/hand_garble.c
    playfair/modified_md5.c
    playfair/omg_hax.c
    playfair/sap_hash.c
)

target_include_directories(airplay_crypto PRIVATE playfair)

find_library(log-lib log)
target_link_libraries(airplay_crypto ${log-lib})
