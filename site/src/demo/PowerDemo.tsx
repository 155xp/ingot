import type { Act } from '../kit/act'
import { Connector, Count, Roll } from '../kit/motion'
import { Stage } from '../kit/stage'
import { Window } from '../kit/window'
import './demo.css'

// Ingot's settings over the game they change. Beats: the cursor switches Power Mode from Battery to Performance; the
// panel's status and the F3 frame rate follow (the display's 120 becomes 586 uncapped); a leader ties the button to
// the line it changed. Plays once in view; the poster is the payoff. Both states are Ingot: vanilla is on the page
// below, beside Ingot at the same spot.
//
// Built from the panel-annotation gold: the game is the product, large, behind, off the bottom; the panel is the
// tool, smaller, in front, straddling the window's right edge over sky, never over the F3 lines. Real strings: the
// window title, the F3 frame-rate line and GPU lines, the panel title, its status, the setting and Done. The frame is
// a real capture of Ingot rendering the world at 2560x1440; both frame rates were measured.

type S = { performance: boolean; linked: boolean }

const initial: S = { performance: false, linked: false }
const poster: S = { performance: true, linked: true }

async function script(a: Act<S>) {
  await a.wait(1200)
  await a.click({ at: 'mode', x: 0.7, y: 0.5 })
  a.put({ performance: true })
  await a.wait(900)
  a.put({ linked: true })
  await a.move({ at: 'panel', x: 0.62, y: 1.18 })
  await a.wait(3000)
}

export default function PowerDemo() {
  return (
    <Stage width={1040} height={580} compact={{ below: 640, width: 358, height: 600 }} theme="dark" initial={initial} poster={poster} script={script} play="once" cursor={{ at: { wide: { x: 1000, y: 548 }, compact: { x: 330, y: 572 } } }} className="pd">
      {(s, rect, layout) => {
        const mode = rect('mode')
        const fps = rect('fps')
        const panel = rect('panel')
        return (
          <>
            <Window title="Minecraft* 26.3 - Singleplayer" className="pd-win">
              <div className="pd-game">
                <img className="pd-shot" src="/shots/world.jpg" alt="" />
                <div className="pd-f3 pd-f3--left">
                  <span className="pd-line" data-target="fps">
                    <Count value={s.performance ? 586 : 120} duration={700} /> fps T: <Roll>{s.performance ? 'inf' : '120'}</Roll> @120Hz
                  </span>
                  <span className="pd-line pd-line--blank" style={{ width: 92 }} />
                  <span className="pd-line pd-line--blank" style={{ width: 128 }} />
                  <span className="pd-gap" />
                  <span className="pd-line pd-line--blank" style={{ width: 150 }} />
                  <span className="pd-line pd-line--blank" style={{ width: 112 }} />
                </div>
                <div className="pd-f3 pd-f3--right">
                  <span className="pd-line">Apple M4 Pro (iGPU)</span>
                  <span className="pd-line">Metal (MTLGPUFamilyApple9)</span>
                </div>
              </div>
            </Window>

            <section className="pd-panel" data-target="panel">
              <div className="pd-title">Ingot Settings</div>
              <div className="pd-status">Now: <Roll>{s.performance ? 'Performance mode' : 'Battery mode'}</Roll></div>
              <span className="pd-btn" data-target="mode" data-cursor="pointer">
                Power Mode: <Roll variant="fade">{s.performance ? 'Performance' : 'Battery'}</Roll>
              </span>
              <span className="pd-btn pd-btn--done">Done</span>
            </section>

            {layout !== 'compact' && panel.width > 0 && fps.width > 0 && (
              <Connector show={s.linked} from={{ x: panel.x - 6, y: mode.y + mode.height / 2 }} to={{ x: fps.x + fps.width + 6, y: fps.y + fps.height / 2 }} route="h" />
            )}
          </>
        )
      }}
    </Stage>
  )
}
