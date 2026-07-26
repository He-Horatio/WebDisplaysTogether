public class UShortToBytes {
	public static void main(String[] args) {
		for (int i = 0; i < Short.MAX_VALUE * 2; i++) {
			short s = (short) (i + Short.MIN_VALUE);
			
			byte[] data = new byte[]{
					(byte) (s & 0xFF),
					(byte) (((s + 128) >> 8) & 0xFF),
			};
			
			int upack = data[0] + (data[1] << 8);
			upack -= Short.MIN_VALUE;
			if (upack < 0)
				upack += 65536;
			
			if (upack != i) {
				System.out.println(upack);
				System.out.println(i);
			}
		}
		
		System.out.println("done");
	}
}
