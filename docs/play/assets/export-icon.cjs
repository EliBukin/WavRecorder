// Exact export of the existing Android vector, not a redesigned/generated logo.
const fs = require('node:fs');
const path = require('node:path');
const sharp = require(require.resolve('sharp', {paths: [process.env.NODE_PATH]}));
const xml = fs.readFileSync(path.join(__dirname, 'ic_launcher.xml'), 'utf8');
const attr = (text, name) => {
  const found = text.match(new RegExp('android:' + name + '="([^"]+)"'));
  if (!found) throw new Error('Missing ' + name);
  return found[1];
};
const width = attr(xml, 'viewportWidth'), height = attr(xml, 'viewportHeight');
const paths = [...xml.matchAll(/<path\s+([\s\S]*?)\/>/g)].map(match => {
  const color = attr(match[1], 'fillColor');
  if (!/^#FF[0-9A-F]{6}$/i.test(color)) throw new Error('Expected opaque ARGB fill');
  return `<path fill="#${color.slice(3)}" d="${attr(match[1], 'pathData')}"/>`;
});
if (paths.length !== 3 || /<group|strokeColor|clip-path/.test(xml)) throw new Error('Unsupported vector structure');
const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="512" height="512" viewBox="0 0 ${width} ${height}">${paths.join('')}</svg>`;
(async () => {
  fs.writeFileSync(path.join(__dirname, 'play-icon.svg'), svg);
  const output = path.join(__dirname, 'play-icon-512.png');
  await sharp(Buffer.from(svg)).ensureAlpha().png().toFile(output);
  const meta = await sharp(output).metadata();
  if (meta.width !== 512 || meta.height !== 512 || meta.channels !== 4 || fs.statSync(output).size > 1024 * 1024) throw new Error('Invalid Play icon');
  console.log(JSON.stringify({file: output, width: meta.width, height: meta.height, channels: meta.channels, bytes: fs.statSync(output).size}));
})();
