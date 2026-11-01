import { useEffect, useRef } from 'react'
import './glyph.css'

export type Panel = { title: string; unit: string; better: string; pre: string }

/**
 * One metric: a column figure drawn in the Glyph kit's glyphs (scripts/columns.mjs), with the kit's arrive motion:
 * the columns rise row by row, Ingot's after vanilla's, then the values and the difference. Reduced motion shows it
 * whole.
 */
export function GlyphPanel({ panel }: { panel: Panel }) {
  const ref = useRef<HTMLDivElement>(null)
  useEffect(() => {
    const pre = ref.current?.querySelector<HTMLElement>('[data-arrive]')
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
    <figure className="gk">
      <figcaption>
        <span className="gk-title">{panel.title} ({panel.unit})</span>
        <span className="gk-hint">{panel.better} is better</span>
      </figcaption>
      <div ref={ref} className="gk-body" dangerouslySetInnerHTML={{ __html: panel.pre }} />
    </figure>
  )
}
