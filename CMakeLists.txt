cmake_minimum_required(VERSION 3.22)
project(mobile-harness VERSION 0.3.0 LANGUAGES CXX)

if(NOT CMAKE_SYSTEM_NAME STREQUAL "Linux")
    message(FATAL_ERROR "The daemon needs Linux and glibc 2.34+ (e.g. Debian 12+).")
endif()

include(FetchContent)
if(POLICY CMP0135)
    cmake_policy(SET CMP0135 NEW)
endif()
FetchContent_Declare(httplib
    URL https://codeload.github.com/yhirose/cpp-httplib/tar.gz/cdcd2ac6e6d5548b5a9130fb39ab76d2c6c614b0
    URL_HASH SHA256=41751bec53719663a2136d00e11c6a3d384c5af71da0d8d6fd731cab10f0ce08
    TLS_VERIFY TRUE
    SOURCE_SUBDIR headers-only)
FetchContent_Declare(json
    URL https://codeload.github.com/nlohmann/json/tar.gz/65ee68451d8eb2b5f3a30b410476ab83deb3289b
    URL_HASH SHA256=13ef31d691947940a08909f8e0772f1d7d68e5da1678ee812a49c4bb0c996b2f
    TLS_VERIFY TRUE
    SOURCE_SUBDIR headers-only)
FetchContent_MakeAvailable(httplib json)
find_package(Threads REQUIRED)
find_package(OpenSSL 3.0 REQUIRED)
find_package(LibXml2 REQUIRED)

add_executable(mobile-harness src/main.cpp src/workspaces.cpp src/jobs.cpp src/process.cpp
    src/provider.cpp src/agent_tools.cpp src/agent.cpp src/web_tools.cpp)
target_compile_features(mobile-harness PRIVATE cxx_std_20)
set_target_properties(mobile-harness PROPERTIES CXX_EXTENSIONS OFF)
target_compile_options(mobile-harness PRIVATE -Wall -Wextra -Wpedantic)
target_include_directories(mobile-harness SYSTEM PRIVATE
    "${httplib_SOURCE_DIR}" "${json_SOURCE_DIR}/single_include")
target_compile_definitions(mobile-harness PRIVATE CPPHTTPLIB_OPENSSL_SUPPORT)
target_link_libraries(mobile-harness PRIVATE Threads::Threads OpenSSL::SSL OpenSSL::Crypto LibXml2::LibXml2)

include(CTest)
if(BUILD_TESTING)
    find_package(Python3 3.8 REQUIRED COMPONENTS Interpreter)
    add_test(NAME daemon.integration COMMAND "${Python3_EXECUTABLE}"
        "${CMAKE_CURRENT_SOURCE_DIR}/tests/daemon_test.py" "$<TARGET_FILE:mobile-harness>")
    set_tests_properties(daemon.integration PROPERTIES TIMEOUT 180)
endif()
