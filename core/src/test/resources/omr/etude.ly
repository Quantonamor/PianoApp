\version "2.24.0"
\header { title = "Kleine Etüde" tagline = ##f }
\paper { #(set-paper-size "a4") }
upper = \relative c'' { \clef treble \key d \major \time 4/4 \tempo 4 = 80
  fis8 e d cis d4 a | b8 cis d e fis2 | g4. fis8 e4 d | cis2 a |
  d16 e fis g a8 fis d4 r | b'4 a8 g fis4 e | d c! b a | d1 \bar "|." }
lower = \relative c { \clef bass \key d \major \time 4/4
  d4 fis a fis | g b d b | e, g b g | a cis e cis |
  d,2 fis | g a | f2 g4 a | d,1 \bar "|." }
\score { \new PianoStaff << \new Staff = "up" \upper \new Staff = "down" \lower >> \layout { } \midi { } }
