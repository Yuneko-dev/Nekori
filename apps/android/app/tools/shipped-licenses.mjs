import path from 'node:path';
export function packageName(directory) {
  const name = path.basename(directory);
  const parent = path.basename(path.dirname(directory));
  return parent.startsWith('@') ? `${parent}/${name}` : name;
}
export function nonEmptyString(value) {
  return typeof value === 'string' && value.trim() ? value.trim() : null;
}
export function customLibrary() {
  return [
    {
      uniqueId: 'js:node-html-markdown@2.0.0',
      artifactVersion: '2.0.0',
      name: 'node-html-markdown',
      description:
        'Fast HTML to markdown converter for NodeJS or the browser. Adapted to work within the Nekori application.',
      website: 'https://www.npmjs.com/package/node-html-markdown',
      developers: [{ name: 'crosstype' }],
      scm: {
        connection: 'scm:git:https://github.com/crosstype/node-html-markdown',
        url: 'https://github.com/crosstype/node-html-markdown',
      },
      licenses: ['js:MIT'],
      funding: [],
    },
    {
      uniqueId: 'npm:hls.js@1.7.3',
      artifactVersion: '1.7.3',
      name: 'hls.js',
      description:
        'JavaScript HLS client using MediaSourceExtension. Adapted to work within the Nekori application.',
      website: 'https://github.com/video-dev/hls.js',
      developers: [{ name: 'video-dev' }],
      scm: {
        url: 'https://github.com/video-dev/hls.js',
        connection: 'scm:git:https://github.com/video-dev/hls.js',
      },
      licenses: ['js:Apache-2.0'],
      funding: [],
    },
  ];
}
