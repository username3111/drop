#!/bin/bash
cd /root/panel/webbuilder
javascript-obfuscator src/app.js   --output public/app.js   --compact true   --identifier-names-generator hexadecimal   --rename-globals false   --string-array true   --string-array-encoding base64   --string-array-rotate true   --string-array-shuffle true   --string-array-calls-transform true   --string-array-index-shift true   --split-strings true   --split-strings-chunk-length 8   --self-defending false
echo Done
