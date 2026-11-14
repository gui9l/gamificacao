/**
 * Nota do dia — planilha que recebe o tempo de tela do celular e entrega para a Nota do computador.
 *
 * Como instalar (uma vez):
 *  1. Crie uma planilha nova no Google Planilhas (nome livre, ex.: "Nota do dia - celular").
 *  2. Extensões > Apps Script. Apague o que estiver lá e cole este arquivo inteiro.
 *  3. Ícone de engrenagem (Configurações do projeto) > Propriedades do script > Adicionar propriedade:
 *       nome: TOKEN    valor: o código secreto que combinamos
 *  4. Implantar > Nova implantação > tipo "App da Web":
 *       Executar como: Eu     |     Quem pode acessar: Qualquer pessoa
 *     Autorize quando pedir. Copie o link que termina em /exec.
 *  5. Esse link vai no app do celular e na Nota do computador.
 */

var ABA = 'uso';
var CABECALHO = ['data', 'total_min', 'insta_min', 'tiktok_min', 'insta_aberturas', 'tiktok_aberturas', 'apps_json', 'atualizado_em',
                 'porta_expulsoes', 'porta_saidas', 'porta_reentradas', 'porta_espera_max'];
var INSTA = ['com.instagram.android', 'com.instagram.lite'];
var TIKTOK = ['com.zhiliaoapp.musically', 'com.ss.android.ugc.trill'];

function aba_() {
  var ss = SpreadsheetApp.getActiveSpreadsheet();
  var s = ss.getSheetByName(ABA);
  if (!s) {
    s = ss.insertSheet(ABA);
    s.appendRow(CABECALHO);
    s.getRange('A:A').setNumberFormat('@');
    s.setFrozenRows(1);
  }
  if (s.getRange(1, 9).getValue() === '') s.getRange(1, 9, 1, 4).setValues([CABECALHO.slice(8, 12)]);  // planilha antiga: títulos das colunas novas
  return s;
}

// ---- Caixa do Jarvis: o celular escreve na notificação da Porta, o PC lê, responde, e o celular mostra a resposta ----
var CAIXA = 'jarvis';

function caixaAba_() {
  var ss = SpreadsheetApp.getActiveSpreadsheet();
  var s = ss.getSheetByName(CAIXA);
  if (!s) {
    s = ss.insertSheet(CAIXA);
    s.appendRow(['id', 'enviado_em', 'texto', 'resposta', 'pergunta', 'respondido_em']);
    s.setFrozenRows(1);
  }
  return s;
}

function caixaLinhas_(s) {
  var n = s.getLastRow();
  return n > 1 ? s.getRange(2, 1, n - 1, 6).getValues() : [];
}

// { token, msg: {id, texto} }  — celular manda uma mensagem
// { token, ver: [ids] }        — celular pergunta se já tem resposta
// { token, resp: [{id, r, p}] } — PC devolve as respostas (p = está esperando um "sim")
function caixa_(corpo) {
  var s = caixaAba_();
  var v = caixaLinhas_(s);
  var onde = {};
  v.forEach(function (r, i) { onde[String(r[0])] = i + 2; });
  if (corpo.msg && corpo.msg.id && !onde[String(corpo.msg.id)]) {
    s.appendRow([String(corpo.msg.id), new Date(), String(corpo.msg.texto || '').slice(0, 1000), '', '', '']);
  }
  (corpo.resp || []).forEach(function (x) {
    var l = onde[String(x.id)];
    if (l) s.getRange(l, 4, 1, 3).setValues([[String(x.r || '-').slice(0, 1000), x.p ? 'sim' : '', new Date()]]);
  });
  var out = [];
  var quer = (corpo.ver || []).map(String);
  if (quer.length) {
    caixaLinhas_(s).forEach(function (r) {
      if (quer.indexOf(String(r[0])) >= 0 && r[3] !== '') out.push({ id: String(r[0]), r: String(r[3]), p: r[4] === 'sim' });
    });
  }
  return resposta_({ ok: true, caixa: out });
}

// Para o PC: mensagens ainda sem resposta dos últimos 2 dias
function caixaNovas_() {
  var limite = Date.now() - 2 * 86400000;
  var novas = [];
  caixaLinhas_(caixaAba_()).forEach(function (r) {
    if (r[3] === '' && r[1] && new Date(r[1]).getTime() > limite) novas.push({ id: String(r[0]), t: String(r[2]), em: new Date(r[1]).toISOString() });
  });
  return resposta_({ ok: true, novas: novas });
}

function resposta_(obj) {
  return ContentService.createTextOutput(JSON.stringify(obj)).setMimeType(ContentService.MimeType.JSON);
}

function tokenOk_(t) {
  var certo = PropertiesService.getScriptProperties().getProperty('TOKEN');
  return !!certo && t === certo;
}

function somar_(apps, pacotes, campo) {
  var soma = 0;
  (apps || []).forEach(function (a) {
    if (pacotes.indexOf(a.pkg) >= 0) soma += Number(a[campo]) || 0;
  });
  return soma;
}

// Recebe do celular: { token, days: [ { date, total, apps: [ { pkg, name, min, opens } ] } ] }
function doPost(e) {
  var lock = LockService.getScriptLock();
  try {
    var corpo = JSON.parse(e.postData.contents);
    if (!tokenOk_(corpo.token)) return resposta_({ ok: false, erro: 'código secreto não confere' });
    lock.waitLock(20000);
    if (corpo.msg || corpo.ver || corpo.resp) return caixa_(corpo);
    // A Nota do computador manda o plano de meta; o celular recebe de volta na resposta.
    if (corpo.plano) PropertiesService.getScriptProperties().setProperty('PLANO', JSON.stringify(corpo.plano));
    var s = aba_();
    var ultima = s.getLastRow();
    var datas = ultima > 1 ? s.getRange(2, 1, ultima - 1, 1).getValues() : [];
    var linhaDe = {};
    for (var i = 0; i < datas.length; i++) linhaDe[String(datas[i][0])] = i + 2;

    (corpo.days || []).forEach(function (d) {
      var linha = [
        String(d.date),
        Number(d.total) || 0,
        somar_(d.apps, INSTA, 'min'),
        somar_(d.apps, TIKTOK, 'min'),
        somar_(d.apps, INSTA, 'opens'),
        somar_(d.apps, TIKTOK, 'opens'),
        JSON.stringify(d.apps || []),
        new Date()
      ];
      var onde = linhaDe[String(d.date)];
      if (onde) {
        s.getRange(onde, 1, 1, linha.length).setValues([linha]);
      } else {
        s.appendRow(linha);
        linhaDe[String(d.date)] = s.getLastRow();
        s.getRange(s.getLastRow(), 1).setNumberFormat('@').setValue(String(d.date));
      }
    });
    // Contadores de hoje da Porta (expulsões, saídas por conta, reentradas que esperaram a trava, maior espera)
    if (corpo.porta && corpo.porta.date) {
      var ondeP = linhaDe[String(corpo.porta.date)];
      if (ondeP) s.getRange(ondeP, 9, 1, 4).setValues([[Number(corpo.porta.exp) || 0, Number(corpo.porta.sai) || 0,
                                                        Number(corpo.porta.ree) || 0, Number(corpo.porta.espMax) || 0]]);
      PropertiesService.getScriptProperties().setProperty('PORTA_LIGADA', String(!!corpo.porta.ligada));
    }
    var pl = PropertiesService.getScriptProperties().getProperty('PLANO');
    return resposta_({ ok: true, plano: pl ? JSON.parse(pl) : null });
  } catch (err) {
    return resposta_({ ok: false, erro: String(err) });
  } finally {
    try { lock.releaseLock(); } catch (x) {}
  }
}

// Entrega para a Nota do computador: ?token=...&dias=14
// Uma linha por dia:  data;total;insta_min;tiktok_min;insta_aberturas;tiktok_aberturas;enviado_em;expulsoes;saidas;reentradas;espera_max
function doGet(e) {
  var p = (e && e.parameter) || {};
  if (!tokenOk_(p.token)) {
    return ContentService.createTextOutput('ERRO;codigo').setMimeType(ContentService.MimeType.TEXT);
  }
  if (p.caixa) return caixaNovas_();
  var dias = Math.max(1, Math.min(60, Number(p.dias) || 14));
  var s = aba_();
  var ultima = s.getLastRow();
  var linhas = [];
  if (ultima > 1) {
    var v = s.getRange(2, 1, ultima - 1, 12).getValues();
    var tz = Session.getScriptTimeZone();
    v.sort(function (a, b) { return String(a[0]) < String(b[0]) ? -1 : 1; });
    v = v.slice(Math.max(0, v.length - dias));
    v.forEach(function (r) {
      var quando = r[7] ? Utilities.formatDate(new Date(r[7]), tz, "yyyy-MM-dd'T'HH:mm") : '';
      linhas.push(r.slice(0, 6).concat([quando], r.slice(8, 12).map(function (x) { return x === '' ? '' : Number(x) || 0; })).join(';'));
    });
  }
  var lig = PropertiesService.getScriptProperties().getProperty('PORTA_LIGADA');
  return ContentService.createTextOutput('OK\n' + linhas.join('\n') + (lig !== null ? '\nPORTA;' + lig : '')).setMimeType(ContentService.MimeType.TEXT);
}
