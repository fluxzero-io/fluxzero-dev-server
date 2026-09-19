"""Organize the selected front-peak helmet into editable Collections."""
import json
from pathlib import Path

import bpy

HERE=Path(__file__).resolve().parent
bpy.ops.wm.open_mainfile(filepath=str(HERE/'fluxzero-helmet.blend'))
scene=bpy.context.scene
pose=bpy.data.objects['Helmet | pose (tilt and yaw)']

names=('Logo','Helm','Korte klep','Oorkappen','Camera en licht')
collections={}
for name in names:
    c=bpy.data.collections.new(name); scene.collection.children.link(c); collections[name]=c

def move(ob, collection):
    for old in list(ob.users_collection): old.objects.unlink(ob)
    collection.objects.link(ob)

for ob in list(scene.objects):
    if ob.name.startswith('Original'):
        group='Logo'
    elif ob.type in ('CAMERA','LIGHT'):
        group='Camera en licht'
    elif 'earmuff' in ob.name:
        group='Oorkappen'
    elif ob.name=='Helmet | full brim' or any(s in ob.name for s in ('central ridge','left rib','right rib')):
        group='Korte klep'
    else:
        group='Helm'
    move(ob,collections[group])

for c in list(bpy.data.collections):
    if c not in collections.values(): bpy.data.collections.remove(c)

layer=scene.view_layers[0]
layer.name='Logo samenstellen'

def choose(earmuffs=True):
    # Ear defenders remain optional; the front peak is part of the selected design.
    layer.layer_collection.children['Oorkappen'].exclude=not earmuffs
    bpy.context.view_layer.update()

for name,c in collections.items():
    c['description']={
        'Logo':'Original Fluxzero paths; shared by every combination.',
        'Helm':'Shared shell, mounting points and pose.',
        'Korte klep':'Selected front peak and matching ribs; keep enabled.',
        'Oorkappen':'Optional ear defenders; enabled by default.',
        'Camera en licht':'Fixed orthographic camera and studio lighting.',
    }[name]
    c.color_tag='COLOR_03' if name in ('Korte klep','Oorkappen') else 'COLOR_04'

for screen in bpy.data.screens:
    for area in screen.areas:
        if area.type=='OUTLINER':
            area.spaces.active.show_restrict_column_enable=True
            area.spaces.active.show_restrict_column_hide=True
            area.spaces.active.show_restrict_column_render=True

readme=bpy.data.texts.new('LEESMIJ — Variaties')
readme.write('''Gebruik de vinkjes bij de Collection-namen in de Outliner:

Oorkappen: vrij aan/uit te zetten, standaard ingeschakeld.
Korte klep: definitieve uitvoering; laat deze ingeschakeld.
Logo, Helm en Camera en licht: laat deze ingeschakeld.

Een uitgevinkt onderdeel verdwijnt uit zowel het werkvenster als de render.
Het oogje beïnvloedt alleen het werkvenster; de camera alleen de render.
De vinkjes zijn daarom de eenvoudigste manier om varianten te kiezen.

Alle objecten blijven afzonderlijk bewerkbaar. Er zijn geen scripts of add-ons
nodig om de groepen te schakelen. Render de gekozen combinatie met F12.
''')
scene['logo_variant']='configurable'
scene['instructions']='Front peak selected; optional Oorkappen enabled by default.'
scene.render.filepath='//configurable-render.png'
choose()
bpy.data.orphans_purge(do_recursive=True)
bpy.context.preferences.filepaths.save_version=0
bpy.ops.wm.save_as_mainfile(filepath=str(HERE/'fluxzero-helmet-configurable.blend'),compress=True)

# Check the optional ear-defender control without generating new design variants.
output=HERE/'variants/collections-check'
output.mkdir(parents=True,exist_ok=True)
report=[]
for ears in (True,False):
    choose(ears)
    state={n:layer.layer_collection.children[n].exclude for n in names}
    assert state['Oorkappen']==(not ears)
    assert not state['Korte klep']
    report.append({'earmuffs':ears,'excluded':state})
(output/'states.json').write_text(json.dumps(report,indent=2)+'\n')
choose()
