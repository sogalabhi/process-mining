const CASES = Number(process.argv[2] ?? 2500);
const START = Date.parse('2026-08-03T00:00:00Z');
const DAYS = 14;

const MIN = 60_000;
const HOUR = 60 * MIN;
const DAY = 24 * HOUR;

let seed = 20260803;
function random() {
  seed = (seed + 0x6d2b79f5) | 0;
  let t = seed;
  t = Math.imul(t ^ (t >>> 15), t | 1);
  t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
  return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
}

function between(min, max) {
  return min + random() * (max - min);
}

function logNormal(median, spread) {
  const u = 1 - random();
  const v = random();
  const z = Math.sqrt(-2 * Math.log(u)) * Math.cos(2 * Math.PI * v);
  return median * Math.exp(spread * z);
}

function isWeekend(time) {
  const day = new Date(time).getUTCDay();
  return day === 0 || day === 6;
}

function arrivalTime() {
  while (true) {
    const day = Math.floor(random() * DAYS);
    const dayStart = START + day * DAY;
    if (isWeekend(dayStart) && random() > 0.35) continue;
    return dayStart + between(8, 20) * HOUR;
  }
}

function buildCase() {
  const events = [];
  let time = arrivalTime();
  const add = (activity, gap) => {
    time += Math.round(gap);
    events.push([activity, time]);
  };

  add('Order Created', 0);

  if (random() < 0.04) return events;

  if (random() < 0.22) {
    const attempts = random() < 0.7 ? 1 : 2;
    for (let i = 0; i < attempts; i++) add('Payment Attempt', between(3, 25) * MIN);
  }
  add('Payment Received', between(2, 20) * MIN);

  const packingDelay = logNormal(6 * HOUR, 0.55) * (isWeekend(time) ? 1.8 : 1);
  add('Packed', packingDelay);

  if (random() < 0.05) return events;

  add('Inspected', between(20, 60) * MIN);
  let rounds = 0;
  while (rounds < 3 && random() < (rounds === 0 ? 0.18 : 0.35)) {
    add('Packed', between(1, 3) * HOUR);
    add('Inspected', between(20, 60) * MIN);
    rounds++;
  }

  add('Shipped', between(1, 4) * HOUR);

  if (random() < 0.06) return events;

  add('Delivered', logNormal(3 * HOUR, 0.4));
  return events;
}

const lines = ['case_id,activity,timestamp'];
for (let i = 1; i <= CASES; i++) {
  const caseId = `GEN-${String(i).padStart(5, '0')}`;
  for (const [activity, time] of buildCase()) {
    const timestamp = new Date(Math.round(time / 1000) * 1000).toISOString().replace('.000Z', 'Z');
    lines.push(`${caseId},${activity},${timestamp}`);
  }
}

process.stdout.write(lines.join('\n') + '\n');
