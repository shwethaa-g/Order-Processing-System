import { useEffect, useMemo, useState } from 'react';
import { fetchOrders, fetchProducts } from './api';
import OrdersTable from './components/OrdersTable';
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

  const productNameById = useMemo(
    () => new Map(products.map((p) => [p.id, p.name])),
    [products]
  );

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
        <h2>Orders</h2>
        <OrdersTable orders={orders} productNameById={productNameById} />
      </section>
    </div>
  );
}

export default App;
