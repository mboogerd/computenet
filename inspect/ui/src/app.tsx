import { onMount } from 'solid-js';
import Canvas from './components/Canvas';
import DetailPanel from './components/DetailPanel';
import Header from './components/Header';
import ToggleBar from './components/ToggleBar';
import { connect } from './solid/state';
import { initTheme } from './solid/theme';
import './app.css';

export default function App() {
  initTheme();
  onMount(() => connect());

  return (
    <div class="app">
      <Header />
      <ToggleBar />
      <div class="app-body">
        <Canvas />
        <DetailPanel />
      </div>
    </div>
  );
}
