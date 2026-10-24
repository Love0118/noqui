// The pinned MIT exporter owns the byte encoder; this adapter supplies headless inputs.
import fs from 'node:fs';
import path from 'node:path';
import {createRequire} from 'node:module';
const require=createRequire(import.meta.url),{PNG}=require('pngjs');
const {loadPlugin}=require('./vendor/objcubed/load-plugin.cjs');
const {makeMemFs}=require('./vendor/objcubed/mem-fs.cjs');
const source=path.join(import.meta.dirname,'vendor/objcubed/objcubed.js');
const request=JSON.parse(fs.readFileSync(process.argv[2],'utf8')),pack=request.pack;
for(const entry of request.entries){
  const image=PNG.sync.read(fs.readFileSync(entry.atlas)),memory=makeMemFs();
  class Image{set src(_){queueMicrotask(()=>this.onload?.());}get naturalWidth(){return image.width;}get naturalHeight(){return image.height;}}
  const api=loadPlugin(source,{globals:{Buffer,Image,setTimeout,process,
    console:{log(){},warn(){},error:console.error},document:{createElement(){return{getContext(){return{drawImage(){},getImageData(){return image;}};}};}},
    Texture:{all:[{uuid:'t0',name:entry.id,source:'data:0',img:{src:'data:0'},uv_width:16,uv_height:16,frameCount:1}]},Outliner:{root:[]},
    Blockbench:{export(){throw Error('Unexpected interactive export');},pickDirectory(){return '/rp';}},Project:{name:entry.id,export_path:''},BarItems:{}},
    requireImpl:id=>id==='fs'?memory:id==='path'?{...path,...path.posix}:require(id)});
  const cfg={texIndex:0,nopow:false,scale:1,offset:entry.offset,colorbehavior:['time','time','time'],duration:1,autoplay:false,
    easing:0,interpolation:0,noshadow:false,autorotate:0,visibility:7,displaySlots:{},flipuv:false,useAtlas:false,texAnimEnabled:false,
    resourcePackDir:'/rp',baseItem:'leather_chestplate',generateDatapack:false,cmdName:entry.id,exportAsEquipment:true,selectedPieces:['chestplate']};
  const result=await api.buildOutput(cfg,[fs.readFileSync(entry.obj,'utf8')],'');
  if(result.nfaces!==entry.faces||result.faceToPart.some(p=>p!==0))throw Error('Geometry loss: '+entry.id);
  await api.saveSingleOutput(result,api.buildDisplayTransforms(cfg),cfg);
  const layers=[];
  for(const [name,data] of memory.writes){
    const match=/\/humanoid\/[^/]+_chestplate_(\d+)\.png$/.exec(name);if(!match)continue;
    const texture=`wearable_back/${entry.id}_${match[1]}`,dest=path.join(pack,`assets/noqui/textures/entity/equipment/humanoid/${texture}.png`);
    fs.mkdirSync(path.dirname(dest),{recursive:true});fs.writeFileSync(dest,data);
    layers.push({index:Number(match[1]),value:{texture:'noqui:'+texture,dyeable:{color_when_undyed:16777215}}});
  }
  layers.sort((a,b)=>a.index-b.index);if(layers.length!==Math.ceil(entry.faces/4))throw Error('Layer loss: '+entry.id);
  const values=layers.map(x=>x.value);
  for(const [prefix,extra] of [['wearable_back',[]],['wearable_back_gravity',JSON.parse(fs.readFileSync(path.join(pack,'assets/noqui/equipment/gravity_suit.json'))).layers.humanoid]]){
    const dest=path.join(pack,`assets/noqui/equipment/${prefix}/${entry.id}.json`);fs.mkdirSync(path.dirname(dest),{recursive:true});
    fs.writeFileSync(dest,JSON.stringify({layers:{humanoid:[...extra,...values]}},null,2)+'\n');
  }
  console.log(entry.id+': '+result.nfaces+' faces / '+values.length+' layers');
}
