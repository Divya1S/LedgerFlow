import { FormEvent, useEffect, useState } from 'react';
import { Account, api, ApiError, formatMoney, FraudAssessment, newIdempotencyKey, PaymentResult } from '../api';

export default function PaymentsView() {
  const [accounts, setAccounts] = useState<Account[]>([]);
  const [source, setSource] = useState('');
  const [merchantId, setMerchantId] = useState('');
  const [amount, setAmount] = useState('');
  const [description, setDescription] = useState('');
  const [payment, setPayment] = useState<PaymentResult | null>(null);
  const [assessment, setAssessment] = useState<FraudAssessment | null>(null);
  const [assessmentError, setAssessmentError] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [refundNote, setRefundNote] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    api.accounts().then(({ data }) => {
      setAccounts(data);
      const wallet = data.find((a) => a.type === 'USER_WALLET');
      if (wallet) setSource(wallet.id);
      const merchant = data.find((a) => a.type === 'MERCHANT');
      if (merchant) setMerchantId(merchant.id);
    }).catch(console.error);
  }, []);

  async function submit(event: FormEvent) {
    event.preventDefault();
    setError(null);
    setPayment(null);
    setAssessment(null);
    setAssessmentError(null);
    setRefundNote(null);
    setBusy(true);
    try {
      const { data } = await api.pay(source, merchantId,
        Math.round(parseFloat(amount) * 100), description, newIdempotencyKey());
      setPayment(data);
      pollAssessment(data.paymentId);
    } catch (e) {
      setError(`${(e as ApiError).code}: ${(e as ApiError).message}`);
    } finally {
      setBusy(false);
    }
  }

  function pollAssessment(paymentId: string, attempt = 0) {
    if (attempt > 15) return;
    setTimeout(async () => {
      try {
        const { data } = await api.fraudAssessment(paymentId);
        setAssessment(data);
        if (!data.aiAssessment && attempt <= 15) pollAssessment(paymentId, attempt + 1);
      } catch (e) {
        const apiError = e as ApiError;
        if (apiError.status === 404 && apiError.code === 'ASSESSMENT_NOT_FOUND') {
          pollAssessment(paymentId, attempt + 1); // verdict not written yet
        } else if (apiError.status === 404) {
          setAssessmentError('Fraud assessments are only visible to the merchant receiving the payment (or an admin). Pay one of YOUR merchant accounts to watch the fraud pipeline.');
        }
      }
    }, 1500);
  }

  async function refund() {
    if (!payment) return;
    try {
      await api.refund(payment.paymentId, newIdempotencyKey());
      setRefundNote('Refund completed: money returned to the payer, fee returned proportionally.');
    } catch (e) {
      setRefundNote(`${(e as ApiError).code}: ${(e as ApiError).message}`);
    }
  }

  return (
    <>
      <div className="panel">
        <h2>Pay a merchant <span className="muted">(1 percent platform fee)</span></h2>
        <form className="stack" onSubmit={submit}>
          <label>
            From wallet
            <select value={source} onChange={(e) => setSource(e.target.value)}>
              {accounts.filter((a) => a.type === 'USER_WALLET')
                .map((a) => <option key={a.id} value={a.id}>{a.name}</option>)}
            </select>
          </label>
          <label>
            Merchant account id <span className="muted">(one of your own merchant accounts shows the full fraud pipeline)</span>
            <input value={merchantId} onChange={(e) => setMerchantId(e.target.value)} required />
          </label>
          <label>
            Amount (USD) <span className="muted">(1000+ triggers fraud REVIEW, 5000+ REJECTED verdict)</span>
            <input type="number" min="0.01" step="0.01" value={amount}
                   onChange={(e) => setAmount(e.target.value)} required />
          </label>
          <label>
            Description
            <input value={description} onChange={(e) => setDescription(e.target.value)} maxLength={500} />
          </label>
          <button className="primary" disabled={busy}>Pay</button>
          {error && <div className="notice error">{error}</div>}
        </form>
      </div>

      {payment && (
        <div className="panel">
          <h2>Payment result</h2>
          <div className="row">
            <span className={`chip ${payment.status}`}>{payment.status}</span>
            <span>{formatMoney(payment.amountMinorUnits)}</span>
            <span className="muted">fee {formatMoney(payment.feeMinorUnits)}</span>
            <span className="mono-small">{payment.paymentId}</span>
            <span className="spacer" />
            <button className="secondary" onClick={refund}>Refund (as merchant)</button>
          </div>
          {refundNote && <div className="notice">{refundNote}</div>}

          {assessment && (
            <div style={{ marginTop: 12 }}>
              <div className="row">
                <strong>Fraud verdict:</strong>
                <span className={`chip ${assessment.verdict}`}>{assessment.verdict}</span>
                <span className="muted">score {assessment.score}, rules {JSON.stringify(assessment.ruleHits)}</span>
              </div>
              {assessment.aiAssessment ? (
                <div className="ai-card">
                  <div className="row">
                    <strong>AI analyst</strong>
                    <span className={`chip ${assessment.aiAssessment.risk_level}`}>
                      {assessment.aiAssessment.risk_level}
                    </span>
                    <span className="model">{assessment.aiModel}</span>
                  </div>
                  <p>{assessment.aiAssessment.summary}</p>
                  <ul>
                    {assessment.aiAssessment.key_factors.map((factor, i) => <li key={i}>{factor}</li>)}
                  </ul>
                  <p className="muted">{assessment.aiAssessment.recommended_action}</p>
                </div>
              ) : (
                assessment.verdict !== 'APPROVED' && (
                  <p className="muted">AI assessment pending (or AI disabled: set AI_ENABLED and GEMINI_API_KEY).</p>
                )
              )}
            </div>
          )}
          {assessmentError && <div className="notice">{assessmentError}</div>}
        </div>
      )}
    </>
  );
}
