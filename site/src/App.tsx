import PowerDemo from './demo/PowerDemo'
import { GlyphPanel } from './figures/GlyphFigure'
import numbers from './figures/panels.json'
import { BlockWordmark } from './BlockWordmark'

const REPO = 'https://github.com/155xp/ingot'
const RELEASE = `${REPO}/releases/tag/v0.1.0-mc26.3`
const JAR = `${REPO}/releases/download/v0.1.0-mc26.3/ingot-0.1.0%2Bmc26.3.jar`
const PACK = `${REPO}/releases/download/v0.1.0-mc26.3/Ingot-0.1.0%2Bmc26.3.mrpack`

export default function App() {
  return (
    <>
      <header className="top">
        <a className="brand" href="/">
          <img src="/ingot.svg" alt="" width={22} height={22} />
          Ingot
        </a>
        <a className="quiet" href={REPO}>GitHub</a>
      </header>

      <main>
        <section className="hero">
          <h1>A performance mod for Apple silicon.</h1>
          <p className="lede">
            Ingot renders Minecraft through Metal. It holds 120 fps on battery, passes 500 on the charger, and needs half
            the memory.
          </p>
          <div className="actions">
            <a className="button" href={JAR}>Download for 26.3</a>
            <a className="button button--quiet" href={`${REPO}/releases`}>All versions</a>
          </div>
        </section>

        <section className="demo" aria-label="Switching Ingot from Performance to Battery mode">
          <PowerDemo />
        </section>

        <section className="shots">
          <p className="line">Straight from the game with F3 open, at 2560 × 1440 on an M4 Pro.</p>
          <div className="shots__strips">
            <img src="/shots/f3-lake-fps.png" width={560} height={126} loading="lazy" alt="Minecraft's F3 screen: 586 fps T: inf (immediate) @120Hz" />
            <img src="/shots/f3-plains-fps.png" width={560} height={126} loading="lazy" alt="Minecraft's F3 screen: 775 fps T: inf (immediate) @120Hz" />
          </div>
        </section>

        <section className="numbers">
          <h2>Half the memory. A third less power.</h2>
          <p className="line">Measured on a MacBook Pro with M4 Pro at full resolution, against vanilla Minecraft.</p>
          <p className="legend">
            <span><i className="legend__swatch legend__swatch--a" />{numbers.series[0]}</span>
            <span><i className="legend__swatch legend__swatch--b" />{numbers.series[1]}</span>
          </p>
          <div className="numbers__grid">
            {numbers.panels.map((panel) => <GlyphPanel key={panel.title} panel={panel} />)}
          </div>
          <p className="source">Source: {numbers.source}</p>
        </section>

        <section className="install">
          <h2>Install</h2>
          <ol>
            <li>
              Put <a href={JAR}>ingot-0.1.0+mc26.3.jar</a> in the mods folder of a Fabric profile for Minecraft 26.3. No
              other mods needed.
            </li>
            <li>
              Or open the <a href={PACK}>Modrinth profile</a> in the Modrinth App. It sets everything up, including the
              memory settings measured above (see the <a href={RELEASE}>release notes</a>).
            </li>
            <li>Choose a power mode at the top of Options › Video Settings. Auto switches to Battery when you unplug.</li>
          </ol>
        </section>
      </main>

      <footer className="foot">
        <div className="foot__row">
          <nav className="foot__links">
            <a href={JAR}>Download for 26.3</a>
            <a href={`${REPO}/releases`}>All versions</a>
            <a href={PACK}>Modrinth profile</a>
            <a href={REPO}>GitHub</a>
          </nav>
          <p className="foot__legal">MIT licensed. Not an official Minecraft product. Not approved by or associated with Mojang or Microsoft.</p>
        </div>
        <BlockWordmark />
      </footer>
    </>
  )
}
