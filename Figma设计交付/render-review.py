# 直接将静态页面数据排版为审阅图片；不控制浏览器，也不执行插件。
import json, math, os
from PIL import Image, ImageDraw, ImageFont, ImageOps
base=os.path.dirname(__file__)
data=json.load(open(base+'/design.json'))
colors=dict(bg='#FBF8F3',surface='#FFFFFF',sand='#F2ECE2',ink='#2B2724',muted='#746B63',brand='#B9512B',brandHover='#923C1F',peach='#F7E4D3',line='#E4DBD0',green='#456759',sage='#E9F0E8',white='#FFFFFF')
fontpath='/System/Library/Fonts/STHeiti Light.ttc'
fonts={}
def font(size):
 size=round(size)
 if size not in fonts:fonts[size]=ImageFont.truetype(fontpath,size)
 return fonts[size]
def wrap(text,w,size):
 lines=[]
 for line in text.split('\n'):
  cur=''
  for c in line:
   if cur and font(size).getlength(cur+c)>w:lines.append(cur);cur=c
   else:cur+=c
  lines.append(cur)
 return lines
def layout(n,w):
 n=dict(n);n['width']=w
 typ=n['type'];px=n.get('padX',n.get('pad',0));py=n.get('padY',n.get('pad',0));gap=n.get('gap',16)
 if typ=='text':n['lines']=wrap(n['text'],w,n['size']);h=len(n['lines'])*n['size']*1.5
 elif typ in ['image','button']:h=n.get('h',48)
 elif typ=='petcard':
  p=n['pet'];small=w<200
  n=layout(dict(type='box',gap=0,bg='surface',radius=20,children=[dict(type='image',image=p['img'],h=n['imageH'],radius=20),dict(type='box',pad=12 if small else 20,gap=8,children=[dict(type='text',text=p['name']+' · '+p['breed'],size=15 if small else 20),dict(type='text',text=p['meta'],size=11 if small else 13,color='muted'),dict(type='text',text=p['price'],size=22 if small else 26,color='brand'),dict(type='text',text='在售 · 到店自提',size=11,color='green')])]),w);return n
 else:
  kids=n.get('children',[]);inner=w-2*px;horizontal=n.get('dir')=='HORIZONTAL'
  if horizontal:
   avail=inner-max(0,len(kids)-1)*gap;fixed=sum(c.get('w',0) for c in kids);flex=sum(not c.get('w') for c in kids);reserve=max(100,avail-fixed)/flex if flex else 0
   ws=[c.get('w',reserve) for c in kids];ratio=min(1,avail/sum(ws)) if sum(ws) else 1;ws=[v*ratio for v in ws]
  else:ws=[min(c.get('w',inner),inner) for c in kids]
  n['kids']=[layout(c,cw) for c,cw in zip(kids,ws)]
  h=2*py+(max([c['height'] for c in n['kids']],default=0) if horizontal else sum(c['height'] for c in n['kids'])+max(0,len(kids)-1)*gap)
 n['height']=max(n.get('h',0),h);return n
def paint(im,n,x,y):
 d=ImageDraw.Draw(im);w=n['width'];h=n['height'];typ=n['type']
 if n.get('bg'):d.rounded_rectangle((round(x),round(y),round(x+w),round(y+h)),radius=n.get('radius',0),fill=colors[n['bg']])
 if typ=='text':
  for i,line in enumerate(n['lines']):d.text((round(x),round(y+i*n['size']*1.5)),line,font=font(n['size']),fill=colors[n.get('color','ink')])
 elif typ=='image':
  photo=ImageOps.fit(Image.open(base+'/assets/'+n['image']+'.jpg').convert('RGB'),(round(w),round(h)))
  mask=Image.new('L',photo.size);ImageDraw.Draw(mask).rounded_rectangle((0,0,photo.width,photo.height),radius=n.get('radius',0),fill=255);im.paste(photo,(round(x),round(y)),mask)
 elif typ=='button':
  bg='line' if n.get('disabled') else 'bg' if n.get('ghost') else 'surface' if n.get('secondary') else 'brand'
  fg='muted' if n.get('disabled') or n.get('ghost') else 'brand' if n.get('secondary') else 'white'
  d.rounded_rectangle((round(x),round(y),round(x+w),round(y+h)),radius=12,fill=colors[bg],outline=colors['line'] if n.get('secondary') else None)
  lines=wrap(n['text'],w-24,15)
  for i,line in enumerate(lines):d.text((round(x+(w-font(15).getlength(line))/2),round(y+(h-len(lines)*21)/2+i*21)),line,font=font(15),fill=colors[fg])
 else:
  px=n.get('padX',n.get('pad',0));py=n.get('padY',n.get('pad',0));cx=x+px;cy=y+py
  for c in n.get('kids',[]):
   offset=(h-2*py-c['height'])/2 if n.get('center') and n.get('dir')=='HORIZONTAL' else 0
   paint(im,c,cx,cy+offset)
   if n.get('dir')=='HORIZONTAL':cx+=c['width']+n.get('gap',16)
   else:cy+=c['height']+n.get('gap',16)
os.makedirs(base+'/review',exist_ok=True)
for key in ['d-home','m-home','a-home','m-checkout','d-detail']:
 s=next(s for s in data['pages'] if s['id']==key);s['gap']=0;n=layout(s,s['w']);im=Image.new('RGB',(s['w'],math.ceil(n['height'])),colors['bg']);paint(im,n,0,0);im.save(base+'/review/'+key+'.png');print(key,im.size)
