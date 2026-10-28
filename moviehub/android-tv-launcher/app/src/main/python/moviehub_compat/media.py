"""Read declared duration and picture size from bounded media bytes; missing metadata stays unknown."""
import re
import struct

def quality_label(width, height):
    """The usual name for a picture size, as VideoDetails.quality names it on Android.

    A wide film frame (1920x800) still counts as 1080p: the width decides when it
    implies a taller 16:9 picture than the height does.
    """
    lines = max(height, round(width * 9 / 16))
    for minimum, label in ((2100, '2160p'), (1400, '1440p'), (1050, '1080p'),
                           (700, '720p'), (560, '576p'), (470, '480p')):
        if lines >= minimum: return label
    return '%dp' % height

def _mp4_size(data, start, end, depth=0):
    """Largest track-header size inside moov/trak boxes in data[start:end], or None."""
    best = None
    while start + 8 <= end and depth <= 3:
        size, name = struct.unpack_from('>I4s', data, start)
        header = 8
        if size == 1:
            if start + 16 > end: return best
            size = struct.unpack_from('>Q', data, start + 8)[0]; header = 16
        if size == 0: size = end - start
        if size < header: return best
        body = start + header
        available = min(end, start + size)
        found = None
        if name in (b'moov', b'trak'):
            found = _mp4_size(data, body, available, depth + 1)
        elif name == b'tkhd' and depth >= 2:
            version = data[body] if body < available else 255
            offset = body + (88 if version == 1 else 76)
            if version in (0, 1) and offset + 8 <= available:
                # 16.16 fixed-point presentation size; audio tracks are 0x0.
                width, height = (value >> 16 for value in struct.unpack_from('>II', data, offset))
                if width and height: found = (width, height)
        if found and (best is None or found[0] * found[1] > best[0] * best[1]): best = found
        start += size
    return best

def mp4_next_box(data):
    """Offset of the first top-level MP4 box that starts beyond data, for a moov stored last."""
    if data[4:8] != b'ftyp': return None
    start = 0
    while start + 8 <= len(data):
        size, name = struct.unpack_from('>I4s', data, start)
        if size == 1:
            if start + 16 > len(data): return None
            size = struct.unpack_from('>Q', data, start + 8)[0]
        if size < 8: return None
        if name == b'moov': return None
        start += size
    return start if start > len(data) else None

def _vint(data, offset, marker):
    """An EBML variable-length integer: (value, length), or (None, length) for unknown size."""
    if offset >= len(data): return None, 0
    first = data[offset]
    length = 1
    while length <= 8 and not first & (0x80 >> (length - 1)): length += 1
    if length > 8 or offset + length > len(data): return None, 0
    value = first if marker else first & (0xff >> length)
    for byte in data[offset + 1:offset + length]: value = (value << 8) | byte
    if not marker and value == (1 << (7 * length)) - 1: return None, length
    return value, length

def _ebml_size(data, start, end, depth=0):
    """PixelWidth/PixelHeight of the largest video track in Matroska/WebM bytes, or None."""
    best = None
    pixels = {}
    while start < end and depth <= 4:
        element, id_length = _vint(data, start, True)
        if not id_length: break
        size, size_length = _vint(data, start + id_length, False)
        if not size_length: break
        body = start + id_length + size_length
        stop = end if size is None else min(end, body + size)
        if element == 0x1F43B675: break  # A cluster means the tracks have already passed.
        if element in (0x18538067, 0x1654AE6B, 0xAE, 0xE0):  # Segment, Tracks, TrackEntry, Video
            found = _ebml_size(data, body, stop, depth + 1)
            if found and (best is None or found[0] * found[1] > best[0] * best[1]): best = found
        elif element in (0xB0, 0xBA) and size and size <= 8 and body + size <= end:
            pixels[element] = int.from_bytes(data[body:body + size], 'big')
        if size is None and element not in (0x18538067,): break
        start = stop if size is not None else end
    if pixels.get(0xB0) and pixels.get(0xBA):
        found = (pixels[0xB0], pixels[0xBA])
        if best is None or found[0] * found[1] > best[0] * best[1]: best = found
    return best

def video_size(data):
    """(width, height) of the video declared in the first bytes of an MP4, MKV or WebM file."""
    if data[:4] == b'\x1a\x45\xdf\xa3':
        return _ebml_size(data, 0, len(data))
    if data[4:8] in (b'ftyp', b'moov', b'free', b'mdat', b'wide'):
        return _mp4_size(data, 0, len(data))
    return None

def mp4_moov_size(data):
    """Picture size from bytes that start at a top-level MP4 box (a moov read separately)."""
    return _mp4_size(data, 0, len(data)) if data[4:8] == b'moov' else None

def duration_seconds(data, kind):
    if b'#EXTM3U' in data[:16]:
        # A rolling live/event playlist is not the full programme duration.
        if b'#EXT-X-ENDLIST' not in data: return None
        values = re.findall(rb'^#EXTINF:([0-9]+(?:\.[0-9]+)?)', data, re.M)
        return sum(float(value) for value in values) if values else None
    if 'dash+xml' in kind:
        match = re.search(rb'mediaPresentationDuration=[\'"]PT(?:(\d+(?:\.\d+)?)H)?(?:(\d+(?:\.\d+)?)M)?(?:(\d+(?:\.\d+)?)S)?[\'"]', data)
        if match: return sum(float(value or 0)*scale for value,scale in zip(match.groups(),(3600,60,1)))
    def boxes(start, end, depth=0):
        if depth > 2: return None
        while start + 8 <= end:
            size, name = struct.unpack_from('>I4s', data, start)
            header = 8
            if size == 1:
                if start + 16 > end: return None
                size = struct.unpack_from('>Q', data, start+8)[0]; header = 16
            if size == 0: size = end-start
            if size < header: return None
            body = start+header
            available = min(end, start+size)
            if name == b'mvhd' and body+20 <= available:
                version = data[body]
                offset = body + (20 if version == 1 else 12)
                width = 12 if version == 1 else 8
                if version not in (0,1) or offset+width > available: return None
                scale = struct.unpack_from('>I', data, offset)[0]
                duration = struct.unpack_from('>Q' if version == 1 else '>I', data, offset+4)[0]
                if scale and duration not in (0xffffffff,0xffffffffffffffff): return duration/scale
            if name == b'moov':
                found = boxes(body, available, depth+1)
                if found is not None: return found
            start += size
        return None
    return boxes(0, len(data))
