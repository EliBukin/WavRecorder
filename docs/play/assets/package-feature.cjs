const fs = require('node:fs');
const path = require('node:path');
const sharp = require(require.resolve('sharp', {paths:[process.env.NODE_PATH]}));
const src = process.argv[2];
if (!src) throw new Error('Pass generated source image path');
(async () => {
  const dest=path.join(__dirname,'feature-graphic-1024x500.png');
  await sharp(src).resize(1024,500,{fit:'contain',background:'#ffffff'})
    .flatten({background:'#ffffff'}).removeAlpha().png().toFile(dest);
  const meta=await sharp(dest).metadata();
  if (meta.width!==1024 || meta.height!==500 || meta.channels!==3) throw new Error('Invalid format');
  console.log(JSON.stringify({path:dest,width:meta.width,height:meta.height,channels:meta.channels,bytes:fs.statSync(dest).size}));
})();
