import axios from 'axios';

const API_BASE = 'http://localhost:3001/api';

const api = axios.create({
  baseURL: API_BASE,
  headers: {
    'Content-Type': 'application/json'
  }
});

export const processAPI = {
  getStatus: () => api.get('/processes/status'),
  start: (name) => api.post(`/processes/${name}/start`),
  stop: (name) => api.post(`/processes/${name}/stop`),
  runTest: () => api.post('/processes/test'),
  runOdlDemo: () => api.post('/testing/opendaylight-demo')
};

export const configAPI = {
  get: (program) => api.get(`/config/${program}`),
  update: (program, content) => api.put(`/config/${program}`, { content })
};

export const udmConfigAPI = {
  list: () => api.get('/udm-configs'),
  get: (id) => api.get(`/udm-configs/${id}`),
  update: (id, content) => api.put(`/udm-configs/${id}`, { content })
};

export const workflowAPI = {
  list: () => api.get('/workflows'),
  get: (category, name) => api.get(`/workflows/${category}/${name}`),
  create: (category, name, content) => api.post(`/workflows/${category}`, { name, content }),
  update: (category, name, content) => api.put(`/workflows/${category}/${name}`, { content }),
  delete: (category, name) => api.delete(`/workflows/${category}/${name}`)
};

export const moduleAPI = {
  getHealth: () => api.get('/modules/health')
};

export const odlAPI = {
  getTopology: () => api.get('/odl/topology'),
  isolateHost: (payload) => api.post('/odl/isolate', payload),
  removeIsolation: (payload) => api.post('/odl/remove-isolation', payload),
  triggerScan: (payload) => api.post('/odl/scan', payload),
  listMitigations: () => api.get('/odl/mitigations'),
  clearMitigation: (id, payload = {}) => api.post(`/odl/mitigations/${id}/clear`, payload),
  extendMitigation: (id, payload) => api.post(`/odl/mitigations/${id}/extend`, payload)
};

export default api;
