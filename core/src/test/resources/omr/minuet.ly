\version "2.24.0"
\header { title = "Menuett G-Dur" composer = "Chr. Petzold" tagline = ##f }
\paper { #(set-paper-size "a4") }
upper = \relative c'' { \clef treble \key g \major \time 3/4 \tempo 4 = 110
  d4 g,8 a b c | d4 g, g | e' c8 d e fis | g4 g, g |
  c d8 c b a | b4 c8 b a g | fis4 g8 a b g | a2. |
  d4 g,8 a b c | d4 g, g | e' c8 d e fis | g4 g, g |
  c d8 c b a | b4 c8 b a g | a4 b8 a g fis | g2. \bar "|." }
lower = \relative c' { \clef bass \key g \major \time 3/4
  <g b d>2 a4 | b2. | c2. | b2. |
  a2. | g2. | d'4 b g | d' d, c' |
  b2 a4 | g2 b4 | c2. | b2 g4 |
  a2 c4 | b2 g4 | c4 d d, | g2. \bar "|." }
\score { \new PianoStaff << \new Staff = "up" \upper \new Staff = "down" \lower >> \layout { } \midi { } }
