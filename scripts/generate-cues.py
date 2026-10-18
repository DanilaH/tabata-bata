"""Original electronic workout cues, reproducible PCM without external assets."""
from pathlib import Path
import math, struct, wave
RATE = 24000
OUT = Path(__file__).resolve().parents[1] / 'app/src/main/res/raw'
def tone(freq, seconds, gain=.85, end=None):
    n = int(seconds * RATE)
    data=[]
    for i in range(n):
        t=i/RATE
        phase=2*math.pi*(freq*t + ((end or freq)-freq)*t*t/(2*seconds))
        envelope=min(1., i/(RATE*.004), (n-1-i)/(RATE*.018))
        data.append(gain*envelope*(.76*math.sin(phase)+.17*math.sin(2*phase)+.07*math.sin(3*phase)))
    return data
def gap(s):return [0.] * int(s*RATE)
def save(name,data):
    assert max(map(abs,data))<1
    with wave.open(str(OUT/(name+'.wav')),'wb') as w:
        w.setnchannels(1);w.setsampwidth(2);w.setframerate(RATE)
        w.writeframes(b''.join(struct.pack('<h',round(v*32767)) for v in data))
    print(name, 'seconds', round(len(data)/RATE,2), 'RMS', round(math.sqrt(sum(v*v for v in data)/len(data)),3))
save('tick',tone(1250,.11,.65))
save('work',tone(950,.12)+gap(.045)+tone(1450,.32,.95))
save('rest',tone(1000,.16)+gap(.045)+tone(650,.24))
save('prepare',tone(800,.25,.8,end=1100))
save('finish',tone(1100,.17,.95)+gap(.07)+tone(1400,.17,.95)+gap(.07)+tone(1750,.42,.95))
