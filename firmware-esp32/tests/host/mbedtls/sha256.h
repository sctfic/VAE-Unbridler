#pragma once
// Use Windows' SHA-256 implementation for host tests; the device links mbedTLS.
#include <windows.h>
#include <bcrypt.h>
#include <vector>
struct mbedtls_sha256_context {
    BCRYPT_ALG_HANDLE algorithm = nullptr;
    BCRYPT_HASH_HANDLE hash = nullptr;
    std::vector<UCHAR> storage;
};
inline void mbedtls_sha256_init(mbedtls_sha256_context*) {}
inline void mbedtls_sha256_free(mbedtls_sha256_context* c) {
    if (c->hash) BCryptDestroyHash(c->hash);
    if (c->algorithm) BCryptCloseAlgorithmProvider(c->algorithm, 0);
    c->hash = nullptr; c->algorithm = nullptr; c->storage.clear();
}
inline int mbedtls_sha256_starts(mbedtls_sha256_context* c, int) {
    mbedtls_sha256_free(c);
    if (BCryptOpenAlgorithmProvider(&c->algorithm, BCRYPT_SHA256_ALGORITHM, nullptr, 0) != 0) return -1;
    DWORD length = 0, result = 0;
    if (BCryptGetProperty(c->algorithm, BCRYPT_OBJECT_LENGTH, reinterpret_cast<PUCHAR>(&length), sizeof(length), &result, 0) != 0) return -1;
    c->storage.resize(length);
    return BCryptCreateHash(c->algorithm, &c->hash, c->storage.data(), length, nullptr, 0, 0);
}
inline int mbedtls_sha256_update(mbedtls_sha256_context* c, const uint8_t* data, size_t size) {
    return BCryptHashData(c->hash, const_cast<PUCHAR>(data), static_cast<ULONG>(size), 0);
}
inline int mbedtls_sha256_finish(mbedtls_sha256_context* c, uint8_t* output) {
    return BCryptFinishHash(c->hash, output, 32, 0);
}
