/*
 * 哈希计算
 */

async function digestMessage(message) {
  const msgUint8 = new TextEncoder().encode(message); // 编码为 UTF-8 Uint8Array
  const hashBuffer = await crypto.subtle.digest('SHA-1', msgUint8); // 计算哈希
  const hashArray = Array.from(new Uint8Array(hashBuffer)); // 转换为字节数组
  const hashHex = hashArray
    .map((b) => b.toString(16).padStart(2, '0'))
    .join(''); // 转换为十六进制字符串
  return {result: hashHex};
}

window['ai_edge_gallery_get_result'] = async (data) => {
  try {
    const jsonData = JSON.parse(data);
    return JSON.stringify(await digestMessage(jsonData['text']));
  } catch (e) {
    console.error(e);
    return JSON.stringify({error: `哈希计算失败: ${e.message}`});
  }
};
