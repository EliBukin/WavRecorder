const path=require('node:path');
const fs=require('node:fs');
const sharp=require(require.resolve('sharp',{paths:[process.env.NODE_PATH]}));
(async()=>{
  const dir=path.join(__dirname,'screenshots'); fs.mkdirSync(dir,{recursive:true});
  for(const name of process.argv.slice(2)){
    const input=path.join(__dirname,name), out=path.join(dir,name);
    const before=await sharp(input).removeAlpha().raw().toBuffer();
    await sharp(input).removeAlpha().png().toFile(out);
    const meta=await sharp(out).metadata();
    if(meta.width!==1080||meta.height!==1920||meta.channels!==3)throw new Error('Invalid screenshot');
    const after=await sharp(out).raw().toBuffer();
    if(!before.equals(after))throw new Error('Screenshot colors changed');
    console.log(name+': 1080x1920 RGB; pixels unchanged');
  }
})();
