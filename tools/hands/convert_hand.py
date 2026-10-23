"""Converts the WebXR "generic-hand" models (npm @webxr-input-profiles/assets, MIT, (c) 2019 Amazon) into the
compact binary format read by core/hands/HandModel.kt.

Format (little endian): int32 vertexCount, int32 triangleCount, int32 jointCount,
  float32[v*3] positions, float32[v*3] normals, uint8[v*4] joints, float32[v*4] weights,
  uint16[t*3] indices, float32[j*3] joint rest positions (model space), then jointCount names (uint8 len + utf8).

Run:  npm pack @webxr-input-profiles/assets && tar xzf webxr-input-profiles-assets-*.tgz
      python3 tools/hands/convert_hand.py package/dist/profiles/generic-hand
"""
import json, os, struct, sys
import numpy as np

src = sys.argv[1]
root = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
out_dir = os.path.join(root, "core/src/main/resources/hands")
os.makedirs(out_dir, exist_ok=True)

def acc(j, b, i):
    a = j["accessors"][i]; bv = j["bufferViews"][a["bufferView"]]
    comp = {5126: "<f4", 5123: "<u2", 5121: "u1", 5125: "<u4"}[a["componentType"]]
    n = {"SCALAR": 1, "VEC2": 2, "VEC3": 3, "VEC4": 4, "MAT4": 16}[a["type"]]
    off = bv.get("byteOffset", 0) + a.get("byteOffset", 0)
    arr = np.frombuffer(b, dtype=comp, count=a["count"] * n, offset=off)
    return arr.reshape(a["count"], n) if n > 1 else arr

for side in ("left", "right"):
    f = open(os.path.join(src, side + ".glb"), "rb").read()
    jl = struct.unpack("<I", f[12:16])[0]
    j = json.loads(f[20:20 + jl]); b = f[20 + jl + 8:]
    prim = j["meshes"][0]["primitives"][0]
    pos = acc(j, b, prim["attributes"]["POSITION"]).astype("<f4")
    nor = acc(j, b, prim["attributes"]["NORMAL"]).astype("<f4")
    jts = acc(j, b, prim["attributes"]["JOINTS_0"]).astype("u1")
    wts = acc(j, b, prim["attributes"]["WEIGHTS_0"]).astype("<f4")
    idx = acc(j, b, prim["indices"]).astype("<u2")
    joints = j["skins"][0]["joints"]
    # joints are siblings under the armature: their translation is their model-space position
    jpos = np.array([j["nodes"][k].get("translation", [0, 0, 0]) for k in joints], dtype="<f4")
    names = [j["nodes"][k]["name"] for k in joints]
    with open(os.path.join(out_dir, "hand_%s.bin" % side), "wb") as o:
        o.write(struct.pack("<iii", len(pos), len(idx) // 3, len(joints)))
        o.write(pos.tobytes()); o.write(nor.tobytes()); o.write(jts.tobytes()); o.write(wts.tobytes())
        o.write(idx.tobytes()); o.write(jpos.tobytes())
        for n in names:
            e = n.encode(); o.write(struct.pack("<B", len(e))); o.write(e)
    print(side, len(pos), "vertices", len(idx) // 3, "triangles", "thumb tip", jpos[names.index("thumb-tip")], "pinky tip", jpos[names.index("pinky-finger-tip")])
open(os.path.join(out_dir, "LICENSE.txt"), "w").write(
    "hand_left.bin / hand_right.bin are converted from the \"generic-hand\" models of the WebXR input profiles\n"
    "(npm @webxr-input-profiles/assets), MIT License, Copyright (c) 2019 Amazon.\n"
    "Converted with tools/hands/convert_hand.py.\n")
