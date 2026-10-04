import json, re, sys, collections
d = json.load(open(sys.argv[1]))
# vertex format names
VF = {
 'Position,Color,UV0,UV2': 'BLOCK',
 'ChunkPosition,ChunkVisibility': 'CHUNK_DATA_INSTANCED',
 'Position,Color,UV0,UV1,UV2,Normal': 'ENTITY',
 'Position,Color,UV0,UV1,UV2,UV3,Normal': 'ENTITY_GLINT_SPECIAL',
 'Position,UV0,Color,UV2': 'PARTICLE',
 'Position': 'POSITION',
 'Position,Color': 'POSITION_COLOR',
 'Position,Color,Normal': 'POSITION_COLOR_NORMAL',
 'Position,Color,UV2': 'POSITION_COLOR_LIGHTMAP',
 'Position,UV0': 'POSITION_TEX',
 'Position,UV0,Color': 'POSITION_TEX_COLOR',
 'Position,Color,UV0,UV2#': 'POSITION_COLOR_TEX_LIGHTMAP',
 'Position,UV0,UV2,Color': 'POSITION_TEX_LIGHTMAP_COLOR',
 'Position,UV0,Color,Normal': 'POSITION_TEX_COLOR_NORMAL',
 'Position,Color,LineWidth': 'POSITION_COLOR_LINE_WIDTH',
 'Position,Color,Normal,LineWidth': 'POSITION_COLOR_NORMAL_LINE_WIDTH',
}
def vfname(v):
    names = ','.join(e.split('@')[0] for e in v['elements'])
    n = VF.get(names, names)
    s = f"{v['slot']}:{n}"
    if v['stepRate']: s += f"(inst/{v['stepRate']})"
    return s
BL = {'none':'-'}
def ct(c):
    if c is None: return 'null'
    s = c['format'].replace('_UNORM','').replace('_FLOAT','F').lower()
    b = c['blend']
    if b.startswith('BlendFunction['):
        b = f"{c['srcColor']},{c['dstColor']}/{c['srcAlpha']},{c['dstAlpha']}".lower()
    if b != 'none': s += ' ' + b.lower()
    if c['writeMask'] != 15: s += f" mask={c['writeMask']}"
    return s
CMP = {'GREATER_THAN_OR_EQUAL':'>=','ALWAYS_PASS':'always','EQUAL':'==','LESS_THAN_OR_EQUAL':'<=','LESS_THAN':'<','GREATER_THAN':'>','NOT_EQUAL':'!=','NEVER_PASS':'never'}
def depth(x):
    if not x: return '-'
    s = CMP[x['test']] + (' W' if x['write'] else ' noW')
    if x['biasScale'] or x['biasConstant']: s += f" bias({x['biasScale']:g},{x['biasConstant']:g})"
    return s
TY = {'UNIFORM_BUFFER':'', 'COMBINED_IMAGE_SAMPLER':'*', 'TEXEL_BUFFER':'^'}
def unis(p):
    ci = p['createInfo']
    names = [u['name'] for u in ci['uniforms']]
    s = ' '.join(f"{i}{TY[u['type']]}{u['name']}" for i,u in enumerate(ci['uniforms']))
    declared = [x.split(':')[0] for x in p['declaredUniforms']]
    extra = [x for x in declared if x not in names]
    if extra: s += ' (+layout-only: ' + ','.join(extra) + ')'
    return s
def short(id):
    return id.replace('minecraft:','')
def defs(p):
    out=[]
    for k,v in p['defines'].items():
        if k in ('OIT_WAVELET_RANK','OIT_COEFF_COUNT','OIT_COEFF_ATTACHMENT_COUNT','OIT'): continue
        out.append(k if v=='' else f"{k}={v}")
    if 'OIT' in p['defines']: out.insert(0,'OIT*')
    return ' '.join(out) or '-'
rows=[]
for p in d:
    ci=p['createInfo']
    sh=p['shaders']
    vs=short(sh['VERTEX']); fs=short(sh['FRAGMENT'])
    shaders = vs if vs.split('/')[-1]==fs.split('/')[-1] and vs.split('/')[0]==fs.split('/')[0] else f"{vs} + {fs}"
    fields = ', '.join(p['fields'])
    reg = {'required':'R','optional':'O','post':'P','unregistered(lazy)':'L'}[p['registration']]
    rows.append((short(p['location']), reg, fields, shaders, defs(p),
        ' '.join(vfname(v) for v in p['vertexFormats']) or '-', unis(p),
        ' / '.join(ct(c) for c in ci['colorTargets']) or '(none)', depth(ci.get('depth')),
        'Y' if ci['cull'] else 'N', 'wire' if ci['polygonMode']=='WIREFRAME' else 'fill', ci['topology']))
print('| location | reg | field(s) | shaders (vsh+fsh) | defines | vertex bindings | CreateInfo.uniforms (index = binding) | color targets | depth | cull | poly | topology |')
print('|---|---|---|---|---|---|---|---|---|---|---|---|')
for r in sorted(rows, key=lambda r:(r[1]=='P', r[0])):
    print('| ' + ' | '.join(x.replace('|','\\|') for x in r) + ' |')
