"""Minimal Mapbox Vector Tile 2.1 decoder and encoder. Standard library only.

Geometry and attribute values are kept as raw protobuf bytes: this module only
needs to drop layers and attributes, never to read coordinates.
Spec: https://github.com/mapbox/vector-tile-spec/blob/master/2.1/vector_tile.proto
"""
from dataclasses import dataclass, field

# Name attributes kept in every feature; every other name:xx / name_xx is dropped.
KEEP_NAMES = frozenset({"name", "name:en", "name_en", "name:latin", "name_int"})


def is_name_key(key):
    return key == "name" or key.startswith("name:") or key.startswith("name_")


def _varint(b, i):
    shift = value = 0
    while True:
        c = b[i]
        i += 1
        value |= (c & 0x7F) << shift
        shift += 7
        if c < 0x80:
            return value, i


def _enc_varint(v):
    out = bytearray()
    while True:
        bits = v & 0x7F
        v >>= 7
        if v:
            out.append(bits | 0x80)
        else:
            out.append(bits)
            return bytes(out)


def _fields(b):
    """Yield (field number, wire type, value) for each field of a message.
    Varints come back as int, everything else as bytes."""
    i = 0
    while i < len(b):
        key, i = _varint(b, i)
        num, wt = key >> 3, key & 7
        if wt == 0:
            v, i = _varint(b, i)
        elif wt == 2:
            n, i = _varint(b, i)
            v = bytes(b[i:i + n])
            i += n
        elif wt == 1:
            v = bytes(b[i:i + 8])
            i += 8
        elif wt == 5:
            v = bytes(b[i:i + 4])
            i += 4
        else:
            raise ValueError(f"unsupported wire type {wt}")
        if i > len(b):
            raise ValueError("truncated message")
        yield num, wt, v


def _len_field(num, payload):
    return _enc_varint((num << 3) | 2) + _enc_varint(len(payload)) + payload


def _int_field(num, v):
    return _enc_varint(num << 3) + _enc_varint(v)


def _unpack(b):
    out, i = [], 0
    while i < len(b):
        v, i = _varint(b, i)
        out.append(v)
    return out


@dataclass
class Feature:
    id: int = None
    tags: list = field(default_factory=list)   # key index, value index, ...
    type: int = None
    geometry: bytes = b""                      # packed command integers, untouched


@dataclass
class Layer:
    name: str
    features: list = field(default_factory=list)
    keys: list = field(default_factory=list)    # str
    values: list = field(default_factory=list)  # raw Value messages (bytes)
    extent: int = None
    version: int = None


def decode(tile):
    layers = []
    for num, _wt, lb in _fields(tile):
        if num != 3:
            raise ValueError(f"unexpected tile field {num}")
        layer = Layer(name="")
        for n, _w, v in _fields(lb):
            if n == 1:
                layer.name = v.decode("utf-8")
            elif n == 2:
                f = Feature()
                for fn, _fw, fv in _fields(v):
                    if fn == 1:
                        f.id = fv
                    elif fn == 2:
                        f.tags = _unpack(fv)
                    elif fn == 3:
                        f.type = fv
                    elif fn == 4:
                        f.geometry = fv
                    else:
                        raise ValueError(f"unexpected feature field {fn}")
                layer.features.append(f)
            elif n == 3:
                layer.keys.append(v.decode("utf-8"))
            elif n == 4:
                layer.values.append(v)
            elif n == 5:
                layer.extent = v
            elif n == 15:
                layer.version = v
            else:
                raise ValueError(f"unexpected layer field {n}")
        layers.append(layer)
    return layers


def encode(layers):
    """Encode in field-number order, as protobuf encoders (and Planetiler) do."""
    out = bytearray()
    for layer in layers:
        lb = bytearray(_len_field(1, layer.name.encode("utf-8")))
        for f in layer.features:
            fb = bytearray()
            if f.id is not None:
                fb += _int_field(1, f.id)
            if f.tags:
                fb += _len_field(2, b"".join(_enc_varint(t) for t in f.tags))
            if f.type is not None:
                fb += _int_field(3, f.type)
            if f.geometry:
                fb += _len_field(4, f.geometry)
            lb += _len_field(2, bytes(fb))
        for k in layer.keys:
            lb += _len_field(3, k.encode("utf-8"))
        for v in layer.values:
            lb += _len_field(4, v)
        if layer.extent is not None:
            lb += _int_field(5, layer.extent)
        if layer.version is not None:
            lb += _int_field(15, layer.version)
        out += _len_field(3, bytes(lb))
    return bytes(out)


def strip(layers, keep_layers, keep_names=KEEP_NAMES):
    """Keep only layers named in keep_layers; in each feature drop name
    attributes not in keep_names. Other attributes stay. Key and value tables
    are rebuilt in first-use order, so unused entries disappear."""
    out = []
    for layer in layers:
        if layer.name not in keep_layers:
            continue
        new = Layer(name=layer.name, extent=layer.extent, version=layer.version)
        key_index, value_index = {}, {}
        for f in layer.features:
            tags = []
            for k, v in zip(f.tags[0::2], f.tags[1::2]):
                key = layer.keys[k]
                if is_name_key(key) and key not in keep_names:
                    continue
                if key not in key_index:
                    key_index[key] = len(new.keys)
                    new.keys.append(key)
                value = layer.values[v]
                if value not in value_index:
                    value_index[value] = len(new.values)
                    new.values.append(value)
                tags += [key_index[key], value_index[value]]
            new.features.append(Feature(id=f.id, tags=tags, type=f.type, geometry=f.geometry))
        out.append(new)
    return out


def attributes(layer, feature):
    """Feature attributes as {key: raw Value bytes}; for tests and checks."""
    return {layer.keys[k]: layer.values[v] for k, v in zip(feature.tags[0::2], feature.tags[1::2])}
