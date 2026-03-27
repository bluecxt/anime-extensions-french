const puppeteer = require('puppeteer');
const fs = require('fs');

(async () => {
  const browser = await puppeteer.launch({ headless: "new" });
  const page = await browser.newPage();
  await page.goto('https://anime-sama.rip/', { waitUntil: 'networkidle2' });
  await new Promise(r => setTimeout(r, 5000));
  const html = await page.content();
  fs.writeFileSync('/tmp/animesamarip_home.html', html);
  await browser.close();
  console.log('Saved to /tmp/animoflix_episode.html');
})();
