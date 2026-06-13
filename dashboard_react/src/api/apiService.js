import axios from 'axios';

const api = axios.create({ baseURL: '/api/v1' });

export async function getProfile(clientId) {
  const { data } = await api.get(`/profiles/${clientId}`);
  return data;
}

export async function getTransactionsByClient(clientId, { limit = 50, before } = {}) {
  const params = { limit };
  if (before) params.before = before;
  const { data } = await api.get(`/transactions/client/${clientId}`, { params });
  return data;
}

export async function getTransaction(txnId) {
  const { data } = await api.get(`/transactions/${txnId}`);
  return data;
}

export async function getEvalResult(txnId) {
  try {
    const { data } = await api.get(`/transactions/results/${txnId}`);
    return data;
  } catch (e) {
    if (e.response?.status === 404) return null;
    throw e;
  }
}

export async function getEvalsByClient(clientId, { limit = 200, before } = {}) {
  const params = { limit };
  if (before) params.before = before;
  const { data } = await api.get(`/transactions/results/client/${clientId}`, { params });
  return data;
}

export async function getReviewQueue({ action, clientId, fromDate, toDate, ruleId, feedbackStatus, limit = 100, before } = {}) {
  const params = { limit };
  if (action) params.action = action;
  if (clientId) params.clientId = clientId;
  if (fromDate) params.fromDate = fromDate;
  if (toDate) params.toDate = toDate;
  if (ruleId) params.ruleId = ruleId;
  if (feedbackStatus) params.feedbackStatus = feedbackStatus;
  if (before) params.before = before;
  const { data } = await api.get('/review/queue', { params });
  return data;
}

export async function getReviewDetail(txnId) {
  const { data } = await api.get(`/review/queue/${txnId}`);
  return data;
}

export async function submitFeedback(txnId, status, feedbackBy) {
  const { data } = await api.post(`/review/queue/${txnId}/feedback`, { status, feedbackBy });
  return data;
}

export async function submitBulkFeedback(txnIds, status, feedbackBy) {
  const { data } = await api.post('/review/queue/bulk-feedback', { txnIds, status, feedbackBy });
  return data;
}

export async function getReviewStats({ fromDate, toDate } = {}) {
  const params = {};
  if (fromDate) params.fromDate = fromDate;
  if (toDate) params.toDate = toDate;
  const { data } = await api.get('/review/stats', { params });
  return data;
}

export async function getWeightHistory({ ruleId, limit = 50, before } = {}) {
  const params = { limit };
  if (ruleId) params.ruleId = ruleId;
  if (before) params.before = before;
  const { data } = await api.get('/review/weight-history', { params });
  return data;
}

export async function getAlertTriage() {
  const { data } = await api.get('/review/queue/triage');
  return data;
}

export async function getAiFeedback(txnId) {
  try {
    const { data } = await api.get(`/review/queue/${txnId}/ai-feedback`);
    return data;
  } catch (e) {
    if (e.response?.status === 404) return null;
    throw e;
  }
}

export async function submitAiFeedback(txnId, helpful, operatorId) {
  const { data } = await api.post(`/review/queue/${txnId}/ai-feedback`, { helpful, operatorId });
  return data;
}

export async function getRulePerformance({ fromDate, toDate } = {}) {
  const params = {};
  if (fromDate) params.fromDate = fromDate;
  if (toDate) params.toDate = toDate;
  const { data } = await api.get('/analytics/rules/performance', { params });
  return data;
}

export async function getClientNetwork(clientId) {
  const { data } = await api.get(`/analytics/graph/client/${clientId}/network`);
  return data;
}

export async function getGraphStatus() {
  const { data } = await api.get('/graph/status');
  return data;
}

export async function getAiFeedbackStats() {
  const { data } = await api.get('/analytics/ai-feedback/stats');
  return data;
}

export async function getClientNarrative(clientId) {
  const { data } = await api.get(`/analytics/client/${clientId}/narrative`);
  return data.narrative;
}

export async function getRules() {
  const { data } = await api.get('/rules');
  return data;
}

export async function updateRule(ruleId, rule) {
  const { data } = await api.put(`/rules/${ruleId}`, rule);
  return data;
}

export async function getThresholds() {
  const { data } = await api.get('/config/thresholds');
  return data;
}

export async function updateThresholds(config) {
  const { data } = await api.put('/config/thresholds', config);
  return data;
}

export async function getFeedbackConfig() {
  const { data } = await api.get('/config/feedback');
  return data;
}

export async function updateFeedbackConfig(config) {
  const { data } = await api.put('/config/feedback', config);
  return data;
}

export async function getTransactionTypes() {
  const { data } = await api.get('/config/transaction-types');
  return data.transactionTypes;
}

export async function updateTransactionTypes(types) {
  const { data } = await api.put('/config/transaction-types', { transactionTypes: types });
  return data.transactionTypes;
}

export async function getAerospikeInfo() {
  const { data } = await api.get('/config/aerospike');
  return data;
}

export async function getSilenceConfig() {
  const { data } = await api.get('/config/silence');
  return data;
}

export async function updateSilenceConfig(config) {
  const { data } = await api.put('/config/silence', config);
  return data;
}

export async function getTwilioConfig() {
  const { data } = await api.get('/config/twilio');
  return data;
}

export async function updateTwilioConfig(config) {
  const { data } = await api.put('/config/twilio', config);
  return data;
}

export async function getOllamaConfig() {
  const { data } = await api.get('/config/ollama');
  return data;
}

export async function updateOllamaConfig(config) {
  const { data } = await api.put('/config/ollama', config);
  return data;
}

export async function getSilenceStatus() {
  const { data } = await api.get('/silence');
  return data;
}

export async function triggerSilenceCheck() {
  const { data } = await api.post('/silence/check');
  return data;
}

export async function sendChatMessage(message) {
  const { data } = await api.post('/chat', { message });
  return data;
}

export async function getSegmentSummary() {
  const { data } = await api.get('/insights/segments/summary');
  return data;
}

export async function getRailInsights() {
  const { data } = await api.get('/insights/rails');
  return data;
}

export async function getCampaigns() {
  const { data } = await api.get('/insights/campaigns');
  return data;
}

export async function getVolumeInsights() {
  const { data } = await api.get('/insights/volume');
  return data;
}

export async function getMigrationOpportunities() {
  const { data } = await api.get('/insights/rails/migration-opportunities');
  return data;
}
