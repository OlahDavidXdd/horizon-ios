import React, {useEffect, useState} from 'react';
import {createRoot} from 'react-dom/client';
import {GlassNav} from '@zakisheriff/liquid-glass';
import '@zakisheriff/liquid-glass/styles.css';
import './nav.css';

const paths = [
  'M8 5 L19 12 L8 19 Z',
  'M12 3 L21 7.5 L21 16.5 L12 21 L3 16.5 L3 7.5 Z M3 7.5 L12 12 L21 7.5 M12 12 L12 21',
  'M9 2 L15 2 L16 5 L19 6 L22 9 L20 12 L20 15 L17 18 L14 18 L12 22 L9 20 L6 20 L3 17 L3 14 L1 12 L3 9 L3 6 L6 3 L9 3 Z M16 12 A4 4 0 1 1 8 12 A4 4 0 1 1 16 12',
];
function App() {
  const [state, setState] = useState({index: 0, labels: ['Play', 'Packs', 'Settings'], reduced: false});
  useEffect(() => {
    window.horizonNav = {
      configure(next) {
        if (!Number.isInteger(next.index) || next.index < 0 || next.index > 2) return;
        if (!Array.isArray(next.labels) || next.labels.length !== 3 || !next.labels.every(x => typeof x === 'string')) return;
        setState({index: next.index, labels: next.labels, reduced: !!next.reduced});
        document.documentElement.classList.toggle('reduced', !!next.reduced);
      },
      backdrop(image) {
        if (typeof image === 'string' && image.startsWith('data:image/png;base64,')) {
          document.getElementById('backdrop').style.backgroundImage = `url("${image}")`;
        }
      },
    };
    window.HorizonNav?.ready();
    return () => {delete window.horizonNav;};
  }, []);
  return <GlassNav
    aria-label="Horizon"
    intensity={6}
    activeIndex={state.index}
    items={state.labels.map((label, i) => ({label, icon:
      <svg viewBox="0 0 24 24" aria-hidden="true"><path d={paths[i]} fill={i === 0 ? 'currentColor' : 'none'} stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round"/></svg>
    }))}
    onChange={index => {
      setState(current => ({...current, index}));
      window.HorizonNav?.select(index);
      window.dispatchEvent(new CustomEvent('horizon-navigation', {detail: index}));
    }}
    style={{width: '100%', '--lg-accent': '#b3ffea'}}
  />;
}
createRoot(document.getElementById('root')).render(<App/>);
