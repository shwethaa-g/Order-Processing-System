import StatusBadge from './StatusBadge';

function OrdersTable({ orders, productNameById }) {
  return (
    <table className="data-table">
      <thead>
        <tr>
          <th>ID</th>
          <th>Customer</th>
          <th>Product</th>
          <th>Qty</th>
          <th>Status</th>
          <th>Retries</th>
        </tr>
      </thead>
      <tbody>
        {orders.map((order) => (
          <tr key={order.id}>
            <td>{order.id}</td>
            <td>{order.customerName}</td>
            <td>{productNameById.get(order.productId) ?? `#${order.productId}`}</td>
            <td>{order.quantity}</td>
            <td><StatusBadge status={order.status} /></td>
            <td>{order.retryCount}</td>
          </tr>
        ))}
        {orders.length === 0 && (
          <tr>
            <td colSpan={6} className="empty-row">No orders yet.</td>
          </tr>
        )}
      </tbody>
    </table>
  );
}

export default OrdersTable;
