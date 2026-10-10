include(${CMAKE_CURRENT_LIST_DIR}/../cmake/wifi_credentials.cmake)

idf_component_register(
    SRCS "main.c"
    INCLUDE_DIRS ""
    REQUIRES acq sps30 net wire store ble
)

# The generated header lands in the build directory, which is gitignored, and
# only this component can see it. Confining the credentials to one translation
# unit is deliberate: net_wifi takes them as arguments, so nothing below main.c
# has a compile-time relationship with a secret.
sps30_generate_wifi_credentials(${CMAKE_BINARY_DIR}/generated)
target_include_directories(${COMPONENT_LIB} PRIVATE ${CMAKE_BINARY_DIR}/generated)
