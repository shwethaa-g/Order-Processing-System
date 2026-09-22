import { useEffect, useState } from 'react';
import { fetchOrders, fetchProducts } from './api';
import './App.css';

const POLL_INTERVAL_MS = 2500;

function App() {
  const [orders, setOrders] = useState([]);
  const [products, setProducts] = useState([]);
  const [lastUpdated, setLastUpdated] = useState(null);
  const [error, setError] = useState(null);

  useEffect(() => {
    let cancelled = false;

    const poll = () => {
      Promise.all([fetchOrders(), fetchProducts()])
        .then(([ordersData, productsData]) => {
          if (cancelled) return;
          setOrders(ordersData);
          setProducts(productsData);
          setLastUpdated(new Date());
          setError(null);
        })
        .catch((err) => {
          if (cancelled) return;
          setError(err.message);
        });
    };

    poll();
    const intervalId = setInterval(poll, POLL_INTERVAL_MS);

    return () => {
      cancelled = true;
      clearInterval(intervalId);
    };
  }, []);

  return (
    <div className="app">
      <header className="app-header">
        <h1>Order Processing Dashboard</h1>
        <div className="status-line">
          {error && <span className="error-text">Backend unreachable: {error}</span>}
          {!error && lastUpdated && (
            <span className="muted">Last updated {lastUpdated.toLocaleTimeString()}</span>
          )}
        </div>
      </header>

      <section>
        <p className="muted">{orders.length} orders, {products.length} products loaded.</p>
      </section>
    </div>
  );
}

export default App;
