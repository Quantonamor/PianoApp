\version "2.24.0"
\header { title = "Ode an die Freude" tagline = ##f }
\paper { #(set-paper-size "a4") }
upper = \relative c'' { \clef treble \key c \major \time 4/4 \tempo 4 = 96
  e4 e f g | g f e d | c c d e | e4. d8 d2 |
  e4 e f g | g f e d | c c d e | d4. c8 c2 |
  d4 d e c | d e8 f e4 c | d e8 f e4 d | c d g,2 |
  e'4 e f g | g f e d | c c d e | d4. c8 c2 \bar "|." }
lower = \relative c { \clef bass \key c \major \time 4/4
  <c g'>1 | <g g'> | <c g'> | <g g'>2 <g g'> |
  <c g'>1 | <g g'> | <c g'> | <g g'>2 <c g'> |
  g'2 c, | g' c, | g' c, | c g |
  <c g'>1 | <g g'> | <c g'> | <g g'>2 <c e g> \bar "|." }
\score { \new PianoStaff << \new Staff = "up" \upper \new Staff = "down" \lower >> \layout { } \midi { } }
