/**
 * 책 스캔 앱 → 구글 드라이브 업로드 중계 (내 구글 계정에서 돌아감)
 *
 * 하는 일: 앱이 요청하면 드라이브 '책스캔/책이름' 폴더를 만들고,
 *          파일을 이어서 올릴 수 있는 업로드 주소를 만들어 앱에 돌려준다.
 *          (파일 자체는 앱이 구글 드라이브로 직접 올림 → 큰 PDF도 OK)
 *
 * 설정: 아래 KEY 를 아무 비밀 단어로 바꾸고, 앱 설정의 '비밀키'에도 똑같이 넣는다.
 */
const KEY = '여기를-나만-아는-단어로-바꾸세요';
const ROOT_NAME = '책스캔';

function doPost(e) {
  try {
    if (!e || !e.parameter || e.parameter.key !== KEY) {
      return json_({ ok: false, error: '비밀키가 달라요 (앱 설정과 Code.gs의 KEY를 같게)' });
    }
    const p = JSON.parse((e.postData && e.postData.contents) || '{}');
    const root = folder_(DriveApp.getRootFolder(), ROOT_NAME);

    if (p.action === 'ping') {
      return json_({ ok: true, folderUrl: root.getUrl() });
    }

    if (p.action === 'start') {
      const folder = folder_(root, String(p.book || '이름없는 책'));
      const res = UrlFetchApp.fetch(
        'https://www.googleapis.com/upload/drive/v3/files?uploadType=resumable',
        {
          method: 'post',
          contentType: 'application/json; charset=UTF-8',
          payload: JSON.stringify({ name: p.name, parents: [folder.getId()], mimeType: p.mime }),
          headers: {
            Authorization: 'Bearer ' + ScriptApp.getOAuthToken(),
            'X-Upload-Content-Type': p.mime,
            'X-Upload-Content-Length': String(p.size),
          },
          muteHttpExceptions: true,
        });
      if (res.getResponseCode() !== 200) {
        return json_({ ok: false, error: 'Drive ' + res.getResponseCode() + ': ' + res.getContentText().slice(0, 300) });
      }
      const h = res.getAllHeaders();
      return json_({ ok: true, url: h['Location'] || h['location'], folderUrl: folder.getUrl() });
    }

    return json_({ ok: false, error: '모르는 요청: ' + p.action });
  } catch (err) {
    return json_({ ok: false, error: String(err) });
  }
}

function doGet() {
  return ContentService.createTextOutput('책 스캔 업로드 서버 작동 중');
}

/** 처음 한 번 편집기에서 실행해서 드라이브 권한을 허용하는 용도 */
function authorize() {
  const root = folder_(DriveApp.getRootFolder(), ROOT_NAME);
  UrlFetchApp.fetch('https://www.googleapis.com/drive/v3/about?fields=user', {
    headers: { Authorization: 'Bearer ' + ScriptApp.getOAuthToken() },
  });
  Logger.log('권한 OK. 폴더: ' + root.getUrl());
}

function folder_(parent, name) {
  const it = parent.getFoldersByName(name);
  return it.hasNext() ? it.next() : parent.createFolder(name);
}

function json_(o) {
  return ContentService.createTextOutput(JSON.stringify(o)).setMimeType(ContentService.MimeType.JSON);
}
