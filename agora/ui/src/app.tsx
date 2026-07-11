import Legend from './components/Legend';
import './app.css';

// WP0 shell. Real views (Debate/Map) mount here from WP2 onward.
export default function App() {
  return (
    <div class="app">
      <header class="app-header">
        <h1>agora</h1>
        <span class="app-tagline">argue, attack the argument, or attack the attack</span>
      </header>
      <main class="app-main">
        <p class="app-placeholder">
          Scaffold ready. Debate and Map views land in later work packages.
        </p>
        <Legend />
      </main>
    </div>
  );
}
