set -e
cd /root/.codebuddy/artifact/2f8a7d30-9c21-4f0b-1d69-851252c9d000/video
S=$(python3 -c "import json;m=json.load(open('vo/meta.json'));print(' '.join(str(int(x['start']*1000)) for x in m))")
set -- $S
FC=""
for i in 0 1 2 3 4 5 6 7 8; do
  eval "D=\${$((i+1))}"
  FC="$FC[$i:a]aformat=sample_rates=48000:channel_layouts=stereo,adelay=${D}|${D}[v$i];"
done
FC="$FC[v0][v1][v2][v3][v4][v5][v6][v7][v8]amix=inputs=9:normalize=0[mix];[mix]acompressor=threshold=-18dB:ratio=2.5:attack=8:release=180,loudnorm=I=-16:TP=-1.5:LRA=11[a]"
ffmpeg -y -loglevel error -i vo/01.mp3 -i vo/02.mp3 -i vo/03.mp3 -i vo/04.mp3 -i vo/05.mp3 -i vo/06.mp3 -i vo/07.mp3 -i vo/08.mp3 -i vo/09.mp3 \
  -filter_complex "$FC" -map "[a]" -c:a aac -b:a 192k -ar 48000 -t 90 vo/mix.m4a
echo MIX_OK
ffprobe -v error -show_entries format=duration -of csv=p=0 vo/mix.m4a
