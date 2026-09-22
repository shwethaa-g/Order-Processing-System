import { useState } from 'react';
import { createOrder } from '../api';

const DEFAULT_BURST_SIZE = 10;

function BurstSubmitButton({ products }) {
  const [productId, setProductId] = useState('');
  const [burstSize, setBurstSize] = useState(DEFAULT_BURST_SIZE);
  const [submitting, setSubmitting] = useState(false);
  const [lastResult, setLastResult] = useState(null);

  const effectiveProductId = productId || (products[0]?.id ?? '');

  const handleSubmit = async () => {
    if (!effectiveProductId) return;
    setSubmitting(true);
    setLastResult(null);

    const requests = Array.from({ length: burstSize }, (_, i) =>
      createOrder({
        customerName: `burst-tester-${i + 1}`,
        productId: Number(effectiveProductId),
        quantity: 1,
      }).then(
        () => ({ ok: true }),
        (err) => ({ ok: false, error: err.message })
      )
    );

    const results = await Promise.all(requests);
    const submitErrors = results.filter((r) => !r.ok).length;
    setLastResult({ total: burstSize, submitErrors });
    setSubmitting(false);
  };

  return (
    <div className="burst-panel">
      <label>
        Product:
        <select value={effectiveProductId} onChange={(e) => setProductId(e.target.value)}>
          {products.map((p) => (
            <option key={p.id} value={p.id}>
              {p.name} ({p.stockQuantity} in stock)
            </option>
          ))}
        </select>
      </label>
      <label>
        Burst size:
        <input
          type="number"
          min="1"
          max="50"
          value={burstSize}
          onChange={(e) => setBurstSize(Number(e.target.value))}
        />
      </label>
      <button onClick={handleSubmit} disabled={submitting || !effectiveProductId}>
        {submitting ? 'Submitting…' : 'Submit test orders'}
      </button>
      {lastResult && (
        <span className="muted">
          Fired {lastResult.total} concurrent requests
          {lastResult.submitErrors > 0 && ` (${lastResult.submitErrors} failed to submit)`}. Watch the tables update.
        </span>
      )}
    </div>
  );
}

export default BurstSubmitButton;
