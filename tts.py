import json, sys, soundfile as sf
from kokoro_onnx import Kokoro
k = Kokoro("kokoro-v1.0.onnx", "voices-v1.0.bin")
lines = json.load(open("script.json"))
voice = sys.argv[1] if len(sys.argv) > 1 else "af_heart"
for l in lines:
    samples, sr = k.create(l["text"], voice=voice, speed=1.0, lang="en-us")
    sf.write(f"out/{l['id']}.wav", samples, sr)
    print(l["id"], round(len(samples) / sr, 2), "s")
