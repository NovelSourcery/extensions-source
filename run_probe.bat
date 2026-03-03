@echo off
cd /d C:\Users\oussama\StudioProjects\extensions-source
python probe_urls.py > probe_output.txt 2>&1
echo Done. See probe_output.txt

