#ifndef BASE64_H
#define BASE64_H

#include <string>
#include <vector>

std::string base64_encode(unsigned char const* bytes_to_encode, unsigned int in_len);
std::string base64_decode(std::string const& encoded_string);

#endif