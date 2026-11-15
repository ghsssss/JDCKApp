import sys
import zipfile

src, dex, dst = sys.argv[1:4]
zin = zipfile.ZipFile(src)
zout = zipfile.ZipFile(dst, 'w', zipfile.ZIP_DEFLATED)
for info in zin.infolist():
    if info.filename == 'classes.dex':
        continue
    if info.filename == 'resources.arsc':
        zi = zipfile.ZipInfo(info.filename, date_time=info.date_time)
        zi.compress_type = zipfile.ZIP_STORED  # targetSdk 30+ 要求不压缩
        zout.writestr(zi, zin.read(info.filename))
    else:
        zout.writestr(info, zin.read(info.filename))
zout.write(dex, 'classes.dex', zipfile.ZIP_DEFLATED)
zout.close()
print('repack OK')
