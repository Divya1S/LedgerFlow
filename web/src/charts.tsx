import { KeyboardEvent, PointerEvent, useState } from 'react';
import { OverviewDay } from './api';
import { categoryLabel, formatCompactMoney, formatDay, formatMoney, formatSigned } from './format';
import { useWidth } from './hooks';

// Charts are drawn by hand as SVG: three small charts do not justify a
// charting library in the bundle, and drawing them directly keeps every
// mark on the design system (colours, radii, hairlines).

/** Rounds a step up to 1, 2 or 5 times a power of ten, so axis labels are round numbers. */
function niceStep(rough: number): number {
  if (rough <= 0) return 1;
  const power = Math.pow(10, Math.floor(Math.log10(rough)));
  const unit = rough / power;
  const nice = unit <= 1 ? 1 : unit <= 2 ? 2 : unit <= 5 ? 5 : 10;
  return nice * power;
}

/** A bar growing up (or down) from the baseline: rounded at the data end, square at the baseline. */
function barPath(x: number, baseline: number, width: number, height: number, up: boolean): string {
  const r = Math.min(4, width / 2, height);
  const end = up ? baseline - height : baseline + height;
  const s = up ? 1 : -1; // direction of travel back towards the baseline
  return [
    `M${x},${baseline}`,
    `L${x},${end + s * r}`,
    `Q${x},${end} ${x + r},${end}`,
    `L${x + width - r},${end}`,
    `Q${x + width},${end} ${x + width},${end + s * r}`,
    `L${x + width},${baseline}`,
    'Z',
  ].join(' ');
}

const HEIGHT = 292;
const MARGIN = { top: 10, right: 4, bottom: 26, left: 46 };

/**
 * Money in and money out per day on one shared axis: in grows up from the
 * baseline, out grows down. Direction is carried by position as well as
 * colour, so the chart still reads without colour vision.
 */
export function CashFlowChart({ days, currency }: { days: OverviewDay[]; currency: string }) {
  const [ref, width] = useWidth<HTMLDivElement>();
  const [active, setActive] = useState<number | null>(null);

  const plotWidth = Math.max(0, width - MARGIN.left - MARGIN.right);
  const plotHeight = HEIGHT - MARGIN.top - MARGIN.bottom;
  const count = days.length;

  const rawIn = Math.max(0, ...days.map((d) => d.moneyInMinorUnits));
  const rawOut = Math.max(0, ...days.map((d) => d.moneyOutMinorUnits));
  const step = niceStep(Math.max(rawIn + rawOut, 10_000) / 4);
  const topValue = Math.max(step, Math.ceil(rawIn / step) * step);
  const bottomValue = Math.ceil(rawOut / step) * step;
  const unit = plotHeight / (topValue + bottomValue);
  const baseline = MARGIN.top + topValue * unit;

  const ticks: number[] = [];
  for (let value = topValue; value >= -bottomValue; value -= step) ticks.push(value);

  const slot = count > 0 ? plotWidth / count : 0;
  const barWidth = Math.max(2, Math.min(24, slot * 0.58));
  const labelEvery = Math.max(1, Math.ceil(count / Math.max(1, Math.floor(plotWidth / 68))));

  function indexAt(event: PointerEvent<SVGRectElement>): number | null {
    if (slot <= 0) return null;
    const box = event.currentTarget.getBoundingClientRect();
    const index = Math.floor((event.clientX - box.left) / slot);
    return Math.min(count - 1, Math.max(0, index));
  }

  function onKeyDown(event: KeyboardEvent<HTMLDivElement>) {
    if (event.key !== 'ArrowLeft' && event.key !== 'ArrowRight') return;
    event.preventDefault();
    const current = active ?? count - 1;
    setActive(Math.min(count - 1, Math.max(0, current + (event.key === 'ArrowRight' ? 1 : -1))));
  }

  const day = active === null ? null : days[active];
  const tipLeft = active === null ? 0
    : Math.min(Math.max(8, MARGIN.left + slot * (active + 0.5) - 92), Math.max(8, width - 190));

  return (
    <div className="chart" ref={ref} tabIndex={0} role="group" onKeyDown={onKeyDown}
         onFocus={() => setActive((current) => current ?? count - 1)} onBlur={() => setActive(null)}
         aria-label="Money in and money out per day. Use the left and right arrow keys to read each day.">
      {width > 0 && (
        <svg height={HEIGHT} viewBox={`0 0 ${width} ${HEIGHT}`} role="img" aria-hidden="true">
          {ticks.map((value) => {
            const y = baseline - value * unit;
            return (
              <g key={value}>
                <line x1={MARGIN.left} x2={width - MARGIN.right} y1={y} y2={y}
                      stroke={value === 0 ? 'var(--chart-axis)' : 'var(--chart-grid)'} strokeWidth="1" />
                <text x={MARGIN.left - 8} y={y + 4} textAnchor="end">{formatCompactMoney(Math.abs(value))}</text>
              </g>
            );
          })}

          {active !== null && (
            <rect x={MARGIN.left + slot * active} y={MARGIN.top} width={slot} height={plotHeight}
                  fill="var(--surface-3)" />
          )}

          {days.map((d, i) => {
            const x = MARGIN.left + slot * i + (slot - barWidth) / 2;
            const up = d.moneyInMinorUnits * unit;
            const down = d.moneyOutMinorUnits * unit;
            return (
              <g key={d.date}>
                {up > 0 && <path d={barPath(x, baseline, barWidth, Math.max(up, 1.5), true)} fill="var(--chart-in)" />}
                {down > 0 && <path d={barPath(x, baseline, barWidth, Math.max(down, 1.5), false)} fill="var(--chart-out)" />}
              </g>
            );
          })}
          <line x1={MARGIN.left} x2={width - MARGIN.right} y1={baseline} y2={baseline}
                stroke="var(--chart-axis)" strokeWidth="1" />

          {days.map((d, i) => (i % labelEvery === 0 || i === count - 1) && (count - 1 - i >= labelEvery / 2 || i === count - 1) && (
            <text key={d.date} x={MARGIN.left + slot * (i + 0.5)} y={HEIGHT - 6}
                  textAnchor={i === count - 1 ? 'end' : 'middle'}
                  dx={i === count - 1 ? slot / 2 : 0}>
              {formatDay(d.date)}
            </text>
          ))}

          <rect x={MARGIN.left} y={MARGIN.top} width={plotWidth} height={plotHeight} fill="transparent"
                onPointerMove={(e) => setActive(indexAt(e))} onPointerLeave={() => setActive(null)} />
        </svg>
      )}

      {day && (
        <div className="chart-tip" style={{ left: tipLeft, top: 4 }}>
          <div className="when">{formatDay(day.date)}</div>
          <div className="line"><i style={{ background: 'var(--chart-in)' }} />Money in<b>{formatMoney(day.moneyInMinorUnits, currency)}</b></div>
          <div className="line"><i style={{ background: 'var(--chart-out)' }} />Money out<b>{formatMoney(day.moneyOutMinorUnits, currency)}</b></div>
          <div className="line net">Net<b>{formatSigned(day.moneyInMinorUnits - day.moneyOutMinorUnits, currency)}</b></div>
        </div>
      )}
    </div>
  );
}

/** The same numbers as the chart, as a table: readable without hovering or seeing colour. */
export function CashFlowTable({ days, currency }: { days: OverviewDay[]; currency: string }) {
  const moving = days.filter((d) => d.moneyInMinorUnits > 0 || d.moneyOutMinorUnits > 0).reverse();
  if (moving.length === 0) {
    return <div className="card-body muted">No money moved in this period.</div>;
  }
  return (
    <div className="table-wrap" style={{ maxHeight: HEIGHT + 40, overflowY: 'auto' }}>
      <table className="table">
        <thead>
          <tr><th>Day</th><th className="right">Money in</th><th className="right">Money out</th>
            <th className="right">Net</th><th className="right">Balance at end of day</th></tr>
        </thead>
        <tbody>
          {moving.map((d) => (
            <tr key={d.date}>
              <td>{formatDay(d.date)}</td>
              <td className="amount">{formatMoney(d.moneyInMinorUnits, currency)}</td>
              <td className="amount">{formatMoney(d.moneyOutMinorUnits, currency)}</td>
              <td className="amount">{formatSigned(d.moneyInMinorUnits - d.moneyOutMinorUnits, currency)}</td>
              <td className="amount">{formatMoney(d.closingBalanceMinorUnits, currency)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

/** Balance over the period as a small trend line beside the headline number. */
export function Sparkline({ values, label }: { values: number[]; label: string }) {
  const [ref, width] = useWidth<HTMLDivElement>();
  const height = 44;
  const pad = 5;
  if (values.length < 2) return <div ref={ref} className="spark" />;

  const min = Math.min(...values);
  const max = Math.max(...values);
  const span = max - min || 1;
  const x = (i: number) => pad + (i / (values.length - 1)) * Math.max(0, width - pad * 2);
  const y = (value: number) => pad + (1 - (value - min) / span) * (height - pad * 2);
  const line = values.map((value, i) => `${i === 0 ? 'M' : 'L'}${x(i).toFixed(1)},${y(value).toFixed(1)}`).join(' ');
  const last = values.length - 1;

  return (
    <div ref={ref} className="spark" role="img" aria-label={label}>
      {width > 0 && (
        <svg width={width} height={height} viewBox={`0 0 ${width} ${height}`} aria-hidden="true">
          <path d={`${line} L${x(last)},${height} L${x(0)},${height} Z`} fill="var(--chart-in)" opacity="0.1" />
          <path d={line} fill="none" stroke="var(--chart-in)" strokeWidth="2" strokeLinejoin="round" strokeLinecap="round" />
          <circle cx={x(last)} cy={y(values[last])} r="4" fill="var(--chart-in)" stroke="var(--surface)" strokeWidth="2" />
        </svg>
      )}
    </div>
  );
}

/** Where money went, largest first. One colour: the bars differ by length, not by hue. */
export function SpendingBars({ slices, currency }: {
  slices: { category: string; amountMinorUnits: number; transactions: number }[]; currency: string;
}) {
  const total = slices.reduce((sum, slice) => sum + slice.amountMinorUnits, 0);
  // Five rows at most: past that, the small ones are folded into one line
  // so the list stays readable and still adds up to the total.
  const shown = slices.length <= 5 ? slices : [
    ...slices.slice(0, 4),
    slices.slice(4).reduce((rest, slice) => ({
      category: 'EVERYTHING_ELSE',
      amountMinorUnits: rest.amountMinorUnits + slice.amountMinorUnits,
      transactions: rest.transactions + slice.transactions,
    }), { category: 'EVERYTHING_ELSE', amountMinorUnits: 0, transactions: 0 }),
  ];
  const largest = Math.max(1, ...shown.map((slice) => slice.amountMinorUnits));
  return (
    <div className="bars">
      {shown.map((slice) => {
        const share = total > 0 ? Math.round((slice.amountMinorUnits / total) * 100) : 0;
        return (
          <div className="bar-row" key={slice.category}>
            <div className="top">
              <span>{categoryLabel(slice.category)}</span>
              <span>{formatMoney(slice.amountMinorUnits, currency)}</span>
            </div>
            <div className="track">
              <div className="fill" style={{ width: `${(slice.amountMinorUnits / largest) * 100}%` }} />
            </div>
            <div className="sub">
              {share}% of money out, {slice.transactions} {slice.transactions === 1 ? 'transaction' : 'transactions'}
            </div>
          </div>
        );
      })}
    </div>
  );
}
