import { useEffect, useRef } from 'react'

// "INGOT" in a 5x7 pixel face, drawn with the Glyph kit's blocks at two pixels per cell (▀ ▄ █), so the footer is
// made of the same glyphs as the figures. It shades in the way the kit's figures arrive: ░ then ▒ then the block,
// column by column from the left.
const FACE: Record<string, string[]> = {
  I: ['#####', '..#..', '..#..', '..#..', '..#..', '..#..', '#####'],
  N: ['#...#', '##..#', '#.#.#', '#..##', '#...#', '#...#', '#...#'],
  G: ['.###.', '#...#', '#....', '#.###', '#...#', '#...#', '.###.'],
  O: ['.###.', '#...#', '#...#', '#...#', '#...#', '#...#', '.###.'],
  T: ['#####', '..#..', '..#..', '..#..', '..#..', '..#..', '..#..'],
}

function cells(word: string) {
  const letters = [...word].map((c) => FACE[c]!)
  const width = letters.length * 6 - 1
  const pixel = (x: number, y: number) => {
    const letter = letters[Math.floor(x / 6)]
    return !!letter && x % 6 < 5 && y < 7 && letter[y]![x % 6] === '#'
  }
  const rows: { ch: string; x: number }[][] = []
  for (let y = 0; y < 8; y += 2) {
    const row: { ch: string; x: number }[] = []
    for (let x = 0; x < width; x++) {
      const top = pixel(x, y)
      const bottom = pixel(x, y + 1)
      row.push({ ch: top && bottom ? '█' : top ? '▀' : bottom ? '▄' : ' ', x })
    }
    rows.push(row)
  }
  return rows
}

const ROWS = cells('INGOT')

export function BlockWordmark() {
  const ref = useRef<HTMLPreElement>(null)
  useEffect(() => {
    const pre = ref.current
    if (!pre || matchMedia('(prefers-reduced-motion: reduce)').matches || !('IntersectionObserver' in window)) return
    pre.classList.add('is-waiting')
    const io = new IntersectionObserver((entries) => {
      if (!entries[0]?.isIntersecting) return
      io.disconnect()
      pre.classList.remove('is-waiting')
      void pre.offsetWidth
      pre.classList.add('is-arriving')
    }, { threshold: 0.4 })
    io.observe(pre)
    return () => io.disconnect()
  }, [])
  return (
    <pre ref={ref} className="wordmark" aria-hidden="true">
      {ROWS.map((row, r) => (
        <span key={r} className="wordmark__row">
          {row.map((c) =>
            c.ch === ' ' ? ' ' : (
              <span key={c.x} className="gk-s" style={{ ['--d' as string]: `${c.x * 28 + r * 10}ms` }}>
                <span className="gk-s1">░</span>
                <span className="gk-s2">▒</span>
                <span className="gk-a">{c.ch}</span>
              </span>
            ),
          )}
          {'\n'}
        </span>
      ))}
    </pre>
  )
}
