const LOW_STOCK_THRESHOLD = 3;

function InventoryTable({ products }) {
  return (
    <table className="data-table">
      <thead>
        <tr>
          <th>Product</th>
          <th>SKU</th>
          <th>Price</th>
          <th>Stock</th>
        </tr>
      </thead>
      <tbody>
        {products.map((product) => {
          const isOut = product.stockQuantity === 0;
          const isLow = !isOut && product.stockQuantity <= LOW_STOCK_THRESHOLD;
          const rowClass = isOut ? 'row-out-of-stock' : isLow ? 'row-low-stock' : '';
          return (
            <tr key={product.id} className={rowClass}>
              <td>{product.name}</td>
              <td>{product.sku}</td>
              <td>{Number(product.price).toFixed(2)}</td>
              <td>
                {product.stockQuantity}
                {isOut && <span className="stock-tag stock-tag-out">OUT</span>}
                {isLow && <span className="stock-tag stock-tag-low">LOW</span>}
              </td>
            </tr>
          );
        })}
        {products.length === 0 && (
          <tr>
            <td colSpan={4} className="empty-row">No products yet.</td>
          </tr>
        )}
      </tbody>
    </table>
  );
}

export default InventoryTable;
