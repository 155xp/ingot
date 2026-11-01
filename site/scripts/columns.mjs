// Draws vertical column panels in the Glyph kit's glyph set. Glyph kit has no vertical bar type, so its rules for
// figures drawn by hand apply (reference/characters.md, reference/layout.md, checked with its lint.mjs). Code draws
// from the numbers, so a column always matches its value and every difference is computed, never typed.
//
//   node scripts/columns.mjs            writes src/figures/panels.json from scripts/panels.columns.json
//   node scripts/columns.mjs --text     prints every panel as plain text (for the kit's lint)
//
// The spec: { source, series: [a, b], panels: [{ title, unit, better: "lower" | "higher", digits,
//   groups: [{ label, values: [a, b] }] }] }. One panel is one metric on one scale: per group a pair of columns,
// series a on the left (receded), series b on the right (the focus), and under the pair how b differs from a.
// A column is a track as tall as the panel's largest value: █ up to its own value, ░ for the rest of the scale.
import { readFileSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'

const COL = 5 // cells per column
const PAIR_GAP = 1
const GROUP_GAP = 3
const H = 8 // rows of column; each row is 8 eighths
const EIGHTHS = ['▁', '▂', '▃', '▄', '▅', '▆', '▇', '█']
const esc = (s) => s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')

// "31% less" when lower is better, "1.5×" when higher is better: how b compares with a
function difference(better, a, b) {
  return better === 'lower' ? `${Math.round((1 - b / a) * 100)}% less` : `${(b / a).toFixed(1)}×`
}

function draw(panel, series) {
  const groupWidth = COL * 2 + PAIR_GAP
  const width = panel.groups.length * groupWidth + (panel.groups.length - 1) * GROUP_GAP
  const max = Math.max(...panel.groups.flatMap((g) => g.values))
  const fmt = (v) => v.toFixed(panel.digits ?? 0)
  // Row 0 holds the values; rows 1..H are the columns. Every column is a full-height track: █ up to its value (a
  // lower eighth on its last cell) and ░ above, the kit's "empty part of a scale".
  const rows = Array.from({ length: H + 1 }, () => Array.from({ length: width }, () => ({ ch: ' ', cls: '', d: -1 })))
  const valueAt = 60 + H * 45 + 220
  panel.groups.forEach((g, gi) => {
    g.values.forEach((v, si) => {
      const x0 = gi * (groupWidth + GROUP_GAP) + si * (COL + PAIR_GAP)
      const level = Math.max(1, Math.round((v / max) * H * 8))
      const full = Math.floor(level / 8)
      const part = level % 8
      const cls = si === 0 ? 'gk-m' : 'gk-d'
      const lag = si === 0 ? 0 : 120 // the focus series arrives last
      for (let r = 0; r < H; r++) {
        const fromBottom = H - 1 - r
        const bar = fromBottom < full ? '█' : fromBottom === full && part ? EIGHTHS[part - 1] : ''
        for (let x = 0; x < COL; x++) {
          rows[r + 1][x0 + x] = bar ? { ch: bar, cls, d: 60 + fromBottom * 45 + lag } : { ch: '░', cls: 'gk-e', d: -1 }
        }
      }
      const text = fmt(v)
      if (text.length > COL) throw new Error(`${panel.title}: "${text}" is wider than its ${COL}-cell column`)
      const tx = x0 + Math.ceil((COL - text.length) / 2)
      for (let i = 0; i < text.length; i++) rows[0][tx + i] = { ch: text[i], cls: si === 0 ? 'gk-val gk-f' : 'gk-val gk-v', d: valueAt + lag }
    })
  })
  const centred = (texts) => {
    const line = Array(width).fill(' ')
    texts.forEach((text, gi) => {
      if (text.length > groupWidth) throw new Error(`${panel.title}: "${text}" is wider than its group`)
      const x = gi * (groupWidth + GROUP_GAP) + Math.floor((groupWidth - text.length) / 2)
      for (let i = 0; i < text.length; i++) line[x + i] = text[i]
    })
    return line.join('').trimEnd()
  }
  const baseline = '─'.repeat(width)
  const labels = centred(panel.groups.map((g) => g.label))
  const diffs = centred(panel.groups.map((g) => difference(panel.better, g.values[0], g.values[1])))
  const text = [...rows.map((r) => r.map((c) => c.ch).join('').trimEnd()), baseline, labels, diffs].join('\n')

  // HTML, in the kit's arrive layers: a bar cell waits as the ░ of its track (gk-s0), passes through ▒ (gk-s2) and
  // lands as its block (gk-a), so a column rises row by row through its own track. Values and the differences last.
  const html = rows.map((r) => {
    let out = ''
    for (let i = 0; i < r.length;) {
      let j = i
      while (j < r.length && r[j].cls === r[i].cls && r[j].d === r[i].d && (r[j].ch === '░') === (r[i].ch === '░')) j++
      const cells = r.slice(i, j)
      const run = esc(cells.map((c) => c.ch).join(''))
      const { cls, d } = r[i]
      if (!cls) out += run
      else if (d < 0) out += `<span class="${cls}">${run}</span>`
      else if (cls === 'gk-m' || cls === 'gk-d') {
        const n = cells.length
        out += `<span class="gk-s" style="--d:${d - 40}ms"><span class="gk-s0 gk-e" aria-hidden="true">${'░'.repeat(n)}</span>`
          + `<span class="gk-s2 ${cls}" aria-hidden="true">${'▒'.repeat(n)}</span><span class="gk-a ${cls}" style="--d:${d}ms">${run}</span></span>`
      } else out += `<span class="gk-a ${cls}" style="--d:${d}ms">${run}</span>`
      i = j
    }
    return out.trimEnd()
  })
  const said = panel.groups.map((g) => `${g.label}: ${series[0]} ${fmt(g.values[0])}, ${series[1]} ${fmt(g.values[1])} ${panel.unit}, ${difference(panel.better, ...g.values)}`).join('; ')
  const pre = `<pre class="gk-figure" data-arrive role="img" aria-label="${esc(`${panel.title} (${panel.unit}, ${panel.better} is better). ${said}.`)}">`
    + html.join('\n') + `\n<span class="gk-f">${baseline}</span>\n${esc(labels)}\n<span class="gk-a gk-diff" style="--d:${valueAt + 300}ms">${esc(diffs)}</span></pre>`
  return { text, figure: { title: panel.title, unit: panel.unit, better: panel.better, pre } }
}

const here = new URL('.', import.meta.url).pathname
const spec = JSON.parse(readFileSync(join(here, 'panels.columns.json'), 'utf8'))
const drawn = spec.panels.map((p) => draw(p, spec.series))
if (process.argv.includes('--text')) {
  for (const [i, d] of drawn.entries()) console.log(`### ${spec.panels[i].title} (${spec.panels[i].unit})\n\n\`\`\`text\n${d.text}\n\`\`\`\n`)
} else {
  writeFileSync(join(here, '../src/figures/panels.json'), JSON.stringify({ series: spec.series, source: spec.source, panels: drawn.map((d) => d.figure) }, null, 2) + '\n')
  console.log('wrote src/figures/panels.json')
}
