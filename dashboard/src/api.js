import axios from 'axios';

const apiClient = axios.create({
  baseURL: 'http://localhost:8080/api',
});

export function fetchProducts() {
  return apiClient.get('/products').then((res) => res.data);
}

export function fetchOrders() {
  return apiClient.get('/orders').then((res) => res.data);
}

export function createOrder(order) {
  return apiClient.post('/orders', order).then((res) => res.data);
}

export default apiClient;
