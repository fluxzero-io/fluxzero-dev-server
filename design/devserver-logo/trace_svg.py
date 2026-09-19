"""Fit a small SVG to Blender's visible-part pass and shaded render.

Requires numpy, scipy, opencv-python-headless and Pillow. The two brand paths
are copied verbatim; only the helmet's rendered boundaries are traced. Each
visible piece has one contour and up to three reusable gradient fills.
"""
import argparse
import json
from pathlib import Path
import xml.etree.ElementTree as ET

import cv2
import numpy as np
from PIL import Image
from scipy.optimize import least_squares

ROOT = Path(__file__).resolve().parents[2]
parser = argparse.ArgumentParser()
parser.add_argument('--directory', type=Path, default=Path(__file__).resolve().parent)
HERE = parser.parse_args().directory.resolve()
source = ET.parse(ROOT/'frontend/public/assets/fluxzero-logo.svg').getroot()
ns = {'s':'http://www.w3.org/2000/svg'}
beauty = np.array(Image.open(HERE/'render.png').convert('RGBA'))
labels = np.array(Image.open(HERE/'visible-parts.png').convert('RGBA'))
parts = json.loads((HERE/'visible-parts.json').read_text())
H,W=beauty.shape[:2]
palette=np.array([p['rgb'] for p in parts if not p['name'].startswith('Original')])
distance=np.sum((labels[:,:,:3].astype(float)[:,:,None,:]-palette)**2,axis=3)
ids=np.argmin(distance,axis=2)
coverage=np.array(Image.open(HERE/'helmet-coverage.png').convert('RGBA'))
valid=(coverage[:,:,0]>128)&(coverage[:,:,3]>128)
rng=np.random.default_rng(58)

def fmt(v): return f'{v:.1f}'.rstrip('0').rstrip('.')
def hexcolor(rgb): return '#'+''.join(f'{int(round(v*255)):02x}' for v in np.clip(rgb,0,1))

def contour_path(mask, tolerance=1.0):
    # Remove single-sample stair steps before fitting compact smooth curves.
    smooth=np.uint8(cv2.GaussianBlur(mask,(5,5),.8)>127)*255
    contours,_=cv2.findContours(smooth,cv2.RETR_LIST,cv2.CHAIN_APPROX_SIMPLE)
    paths=[]
    for contour in contours:
        if abs(cv2.contourArea(contour))<8: continue
        pts=cv2.approxPolyDP(contour,tolerance,True)[:,0,:].astype(float)+.5
        if len(pts)<3: continue
        # Smooth the contour with short cubic segments; keep corner tangents local.
        paths.append('M'+','.join(map(fmt,pts[0])))
        for i in range(len(pts)):
            p0,p1,p2,p3=[pts[j%len(pts)] for j in (i-1,i,i+1,i+2)]
            c1=p1+(p2-p0)/6; c2=p2-(p3-p1)/6
            paths.append('c'+' '.join(','.join(map(fmt,p-p1)) for p in (c1,c2,p2)))
        paths.append('Z')
    return ''.join(paths)

def model(p,x,y,shadow):
    color=p[:3][None,:]*(1-y[:,None])+p[3:6][None,:]*y[:,None]
    for k,shade in enumerate((np.ones(3),shadow)):
        cx,cy,rx,ry,opacity=p[6+k*5:11+k*5]
        r=np.sqrt(((x-cx)/rx)**2+((y-cy)/ry)**2)
        alpha=opacity*np.maximum(0,1-r)**2
        color=color*(1-alpha[:,None])+shade[None,:]*alpha[:,None]
    return color

pieces=[]; metrics=[]
# Paint behind/front regions deterministically. Masks already contain exact depth visibility.
for index,part in enumerate(parts):
    if part['name'].startswith('Original'): continue
    mask=np.uint8((ids==index)&valid)*255
    mask=cv2.morphologyEx(mask,cv2.MORPH_CLOSE,np.ones((3,3),np.uint8))
    mask[~valid]=0
    # Close only single-pixel sampling gaps, never bridge separate components.
    count,connected,stats,_=cv2.connectedComponentsWithStats(mask)
    for n in range(1,count):
        if stats[n,cv2.CC_STAT_AREA]<150: continue
        region=np.uint8(connected==n)*255
        yy,xx=np.where(cv2.erode(region,np.ones((3,3),np.uint8))>0)
        if len(xx)<100: continue
        xmin,ymin,xmax,ymax=xx.min(),yy.min(),xx.max(),yy.max()
        width,height=max(1,xmax-xmin),max(1,ymax-ymin)
        sample=rng.choice(len(xx),min(len(xx),1800),replace=False)
        x=(xx[sample]-xmin)/width; y=(yy[sample]-ymin)/height
        target=beauty[yy[sample],xx[sample],:3]/255
        shadow=np.array([.95,.20,0]) if not part['name'].startswith('Mount') else np.array([.03,.04,.05])
        top=np.median(target[y<.35],axis=0) if np.any(y<.35) else np.median(target,axis=0)
        bottom=np.median(target[y>.65],axis=0) if np.any(y>.65) else top
        start=np.r_[top,bottom,.55,.15,.8,.7,.3, 0,.8,.5,1,.15]
        lower=np.r_[np.zeros(6), -1,-1,.08,.08,0, -1,-1,.08,.08,0]
        upper=np.r_[np.ones(6), 2,2,4,4,.9, 2,2,4,4,.9]
        fit=least_squares(lambda p:(model(p,x,y,shadow)-target).ravel(),start,bounds=(lower,upper),max_nfev=130,ftol=1e-5,loss='soft_l1',f_scale=.04)
        error=float(np.mean(np.abs(model(fit.x,x,y,shadow)-target))*255)
        piece={'name':part['name'],'d':contour_path(region),'p':fit.x,'shadow':shadow,'bbox':(xmin,ymin,width,height)}
        pieces.append(piece); metrics.append({'part':part['name'],'pixels':len(xx),'mean_rgb_error':round(error,2)})
        print(part['name'],len(xx),'pixels; RGB error',round(error,2),flush=True)

defs=['<linearGradient id="fluxzero-logo-gradient" x1="143.23" y1="259.88" x2="98.04" y2="3.61" gradientUnits="userSpaceOnUse"><stop offset="0" stop-color="#d6effb"/><stop offset=".99" stop-color="#3582bc"/></linearGradient>']
for ident,color in [('glow','#fff'),('shade','#f23300'),('mount-shade','#080a0d')]:
    stops=''.join(f'<stop offset="{t}" stop-color="{color}" stop-opacity="{(1-t)**2:.4f}"/>' for t in (0,.25,.5,.75,1))
    defs.append(f'<radialGradient id="{ident}" cx="0" cy="0" r="1" gradientUnits="userSpaceOnUse">{stops}</radialGradient>')
body=[]
scale=W/8.8*5.3/241.27
translation_x=W/2-241.27/2*scale
translation_y=H/2-(263.49/2*5.3/241.27-.05-.7)*H/8.8
body.append(f'<g id="fluxzero-original" transform="translate({fmt(translation_x)} {fmt(translation_y)}) scale({scale:.8f})">')
for i,path in enumerate(source.findall('s:path',ns)):
    fill='url(#fluxzero-logo-gradient)' if i==0 else '#fff'
    body.append(f'<path fill="{fill}" d="{path.attrib["d"]}"/>')
body.append('</g>')
helmet_mask=np.uint8(valid)*255
# The visible geometry also clips all shading: a back-facing rim must not
# spill across the unchanged brand path. Dark backing cannot create orange
# seams between the cushion and its housing.
silhouette=contour_path(helmet_mask,.75)
defs.append('<path id="equipment-silhouette" d="'+silhouette+'"/>')
defs.append('<clipPath id="equipment-visible"><use href="#equipment-silhouette" clip-rule="evenodd"/></clipPath>')
body.append('<g clip-path="url(#equipment-visible)">')
body.append('<use href="#equipment-silhouette" fill="#252d36" fill-rule="evenodd"/>')
orange_parts=np.array([p['name'].startswith('Helmet') for p in parts if not p['name'].startswith('Original')])
orange_mask=np.uint8(valid & orange_parts[ids])*255
body.append('<path fill="#ff8828" fill-rule="evenodd" d="'+contour_path(orange_mask)+'"/>')
for i,piece in enumerate(pieces):
    p=piece['p']; x,y,w,h=piece['bbox']; ident=f'h{i}'
    defs.append(f'<path id="{ident}" fill-rule="evenodd" d="{piece["d"]}"/>')
    defs.append(f'<linearGradient id="{ident}b" x1="0" y1="{y}" x2="0" y2="{y+h}" gradientUnits="userSpaceOnUse"><stop stop-color="{hexcolor(p[:3])}"/><stop offset="1" stop-color="{hexcolor(p[3:6])}"/></linearGradient>')
    body.append(f'<use href="#{ident}" fill="url(#{ident}b)" stroke="url(#{ident}b)" stroke-width="1"/>')
    for j,shade in enumerate(('#fff',hexcolor(piece['shadow']))):
        cx,cy,rx,ry,opacity=p[6+j*5:11+j*5]
        if opacity<.015: continue
        grad=f'{ident}g{j}'
        base='glow' if j==0 else 'mount-shade' if piece['name'].startswith('Mount') else 'shade'
        defs.append(f'<radialGradient id="{grad}" href="#{base}" gradientTransform="translate({fmt(x+cx*w)} {fmt(y+cy*h)}) scale({fmt(rx*w)} {fmt(ry*h)})"/>')
        body.append(f'<use href="#{ident}" fill="url(#{grad})" opacity="{opacity:.3f}"/>')
body.append('</g>')
svg=f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {W} {H}" role="img" aria-labelledby="title">\n<title id="title">Fluxzero dev server</title>\n<desc>Original Fluxzero paths with an orange helmet projected from the Blender source model. Transparent vector artwork.</desc>\n<defs>\n'+ '\n'.join(defs)+'\n</defs>\n'+'\n'.join(body)+'\n</svg>\n'
(HERE/'projected-logo.svg').write_text(svg)
(HERE/'fit-metrics.json').write_text(json.dumps(metrics,indent=2)+'\n')
print('SVG:',len(svg.encode()),'bytes;',len(pieces)+4,'paths')
