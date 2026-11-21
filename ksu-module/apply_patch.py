import hashlib, os, sys
SRC=r'g:\LSP_ksu模块\camera_fix\2026-11-22\camera-launch-diagnosis\work\libmorpho_video_stabilizer.so'
DST=r'g:\LSP_ksu模块\camera_fix\2026-11-22\camera-launch-diagnosis\work\patch\libmorpho_video_stabilizer.so'
ORIG_SHA='c05691a380479ccd8d7fb78d8e6fec990e8bbe2d907586d2f1edf8fe92e7501b'
OFF=0x408380
OLD=bytes.fromhex('f48345b9')  # ldr w20,[sp,#0x580]
NEW=bytes.fromhex('34008052')  # mov w20,#1 (movz w20,#1)
d=bytearray(open(SRC,'rb').read())
sha=hashlib.sha256(d).hexdigest()
print('original sha256',sha)
assert sha==ORIG_SHA, 'original hash mismatch'
assert d[OFF:OFF+4]==OLD, 'unexpected bytes at 0x408380: '+d[OFF:OFF+4].hex()
d[OFF:OFF+4]=NEW
open(DST,'wb').write(d)
print('patched  sha256',hashlib.sha256(d).hexdigest())
print('offset',hex(OFF),'old',OLD.hex(),'new',NEW.hex())
