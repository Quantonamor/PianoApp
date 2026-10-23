"""Rewrites truth.txt from score.musicxml in every sub-folder of the given directory
(default: $OMR_WORK/corpus2). Usage: truth.py [DIR]"""
import os, sys
from music21 import converter
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from truthlib import write_truth
D = sys.argv[1] if len(sys.argv) > 1 else os.path.join(os.environ.get('OMR_WORK', '/tmp/omr-work'), 'corpus2')
for n in sorted(os.listdir(D)):
    xml = os.path.join(D, n, 'score.musicxml')
    if not os.path.exists(xml): continue
    write_truth(converter.parse(xml), os.path.join(D, n))
    print(n)
