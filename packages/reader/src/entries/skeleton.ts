import van from 'vanjs-core';

interface SkeletonConfig {
  background: string;
  base: string;
  highlight: string;
  fontSize: number;
  lineGap: number;
  lineHeight: number;
  marginTop: number;
  marginRight: number;
  marginBottom: number;
  marginLeft: number;
}

const configElement = document.getElementById('nekori-skeleton-config');
const root = document.getElementById('skeleton');
if (configElement?.textContent && root) {
  const config = JSON.parse(configElement.textContent) as SkeletonConfig;
  const values = {
    background: config.background, base: config.base, highlight: config.highlight,
    'font-size': `${config.fontSize}px`, 'line-gap': `${config.lineGap}px`,
    'margin-top': `${config.marginTop}px`, 'margin-right': `${config.marginRight}px`,
    'margin-bottom': `${config.marginBottom}px`, 'margin-left': `${config.marginLeft}px`,
  };
  for (const [name, value] of Object.entries(values)) {
    document.documentElement.style.setProperty(`--skeleton-${name}`, value);
  }
  let availableHeight = window.innerHeight - 10;
  let consecutiveLines = 0;
  const items: boolean[] = [];
  while (availableHeight > config.lineHeight) {
    if (Math.random() * 4 > 1 && consecutiveLines <= 5) {
      items.push(true);
      availableHeight -= config.lineHeight;
      consecutiveLines++;
    } else {
      items.push(false);
      availableHeight -= 16;
      consecutiveLines = 0;
    }
  }
  const { div } = van.tags;
  items.forEach((isLine, index) => {
    const endsParagraph = items[index + 1] !== undefined && !items[index + 1];
    const width = endsParagraph ? Math.max(0.1, Math.random()) * 90 : 90;
    van.add(root, endsParagraph || isLine
      ? div({ class: 'line', style: `width:${width}vw` }, div({ class: 'shimmer' }))
      : div({ class: 'gap' }));
  });
}
