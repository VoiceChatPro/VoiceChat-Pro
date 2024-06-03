import javax.sound.sampled.*;
import java.io.*;
import java.net.*;
import java.util.*;

public class VoiceChatSerwer {
    private static final int CHAT_PORT = 12346; // Port dla czatu tekstowego
    private static final int VOICE_PORT = 5000; // Port dla czatu głosowego

    private List<String> existingChannels; // Lista przechowująca istniejące kanały

    private Map<String, Integer> userChannelCount; // Mapa przechowująca liczbę kanałów utworzonych przez każdego użytkownika

    private Map<String, List<String>> channelMessages;
    // Mapa przechowująca użytkowników oraz kanały, do których są przypisani
    private Map<String, String> userChannels;
    // Mapa przechowująca użytkowników czatu tekstowego
    private Map<String, Socket> clientMap;

    // Format audio dla transmisji głosowej
    private AudioFormat audioFormat;

    // Linia wejściowa (do nagrywania) na serwerze
    private TargetDataLine targetDataLine;

    // Lista klientów czatu głosowego
    private List<DataOutputStream> voiceClients;

    // Lista gniazd dla czatu głosowego
    private List<Socket> voiceSockets;

    // Lista dostępnych kanałów czatu
    private List<String> chatRooms;

    private boolean channelCreationInProgress = false; // Dodaj zmienną do śledzenia, czy trwa proces tworzenia kanału

    public VoiceChatSerwer() {
        existingChannels = new ArrayList<>();
        userChannelCount = new HashMap<>();
        clientMap = new HashMap<>(); // Inicjalizacja mapy użytkowników czatu tekstowego
        voiceClients = new ArrayList<>(); // Inicjalizacja listy klientów czatu głosowego
        voiceSockets = new ArrayList<>(); // Inicjalizacja listy gniazd dla czatu głosowego
        chatRooms = new ArrayList<>(); // Inicjalizacja listy dostępnych kanałów czatu
        chatRooms.add("Kanał 1"); // Dodanie domyślnego kanału

        try {
            // Ustawienie formatu audio
            audioFormat = getAudioFormat();

            // Inicjalizacja linii wejściowej (do nagrywania) na serwerze
            DataLine.Info dataLineInfo = new DataLine.Info(TargetDataLine.class, audioFormat);
            targetDataLine = (TargetDataLine) AudioSystem.getLine(dataLineInfo);
            targetDataLine.open(audioFormat);
            targetDataLine.start();

            // Inicjalizacja serwera czatu tekstowego
            startChatServer();

            // Inicjalizacja serwera czatu głosowego
            startVoiceServer();

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // Metoda startChatServer() inicjuje serwer czatu tekstowego
    private void startChatServer() {
        new Thread(() -> {
            try (ServerSocket serverSocket = new ServerSocket(CHAT_PORT)) {
                System.out.println("Port serwera:  " + CHAT_PORT);
                while (true) {
                    Socket clientSocket = serverSocket.accept();
                    new Thread(() -> handleChatClient(clientSocket)).start();
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }).start();
    }


    // Metoda handleChatClient() na serwerze obsługuje nowego klienta czatu tekstowego
    private void handleChatClient(Socket clientSocket) {
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
            String userName = reader.readLine();
            String channelName = reader.readLine(); // Odczytaj nazwę kanału od klienta
            System.out.println("Dołączył nowy użytkownik: " + userName);
            clientMap.put(userName, clientSocket);

            String message;
            while ((message = reader.readLine()) != null) {
                if (message.startsWith("Channel removed: ")) {
                    String removedChannelName = message.substring("Channel removed: ".length());
                    removeChannel(removedChannelName);
                    // Powiadom wszystkich klientów o usunięciu kanału
                    sendMessageToAllClients(message, "Server", null);
                } else {
                    // Sprawdź, czy wiadomość nie jest od serwera
                    if (!userName.equals("Server")) {
                        // Sprawdź, czy wiadomość nie jest powiadomieniem o zmianie kanału
                        if (message.startsWith("Changed channel: ")) {
                            String newChannel = message.substring("Changed channel: ".length());
                            sendMessageToAllClients("(Server) User " + userName + " zmienił kanał na: " , "Server", newChannel);
                            channelName = newChannel; // Aktualizuj nazwę bieżącego kanału
                        } else {
                            // Przekaż wiadomość do wszystkich klientów bez dodawania nazwy kanału
                            sendMessageToAllClients(message, userName, channelName);
                        }
                    } else {
                        // Wiadomość od serwera
                        sendMessageToAllClients("(Server) " + message, "Server", null);
                    }
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            try {
                clientSocket.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
            clientMap.values().remove(clientSocket);
        }
    }





    // Metoda startVoiceServer() inicjuje serwer czatu głosowego
    private void startVoiceServer() {
        new Thread(() -> {
            try (ServerSocket serverSocket = new ServerSocket(VOICE_PORT)) {
                System.out.println("Voice server started on port " + VOICE_PORT);
                while (true) {
                    Socket socket = serverSocket.accept();
                    System.out.println("Voice client connected: " + socket.getInetAddress());
                    DataOutputStream dataOutputStream = new DataOutputStream(socket.getOutputStream());
                    voiceClients.add(dataOutputStream);
                    voiceSockets.add(socket);
                    // Uruchom wątek do przesyłania dźwięku dla nowo połączonego klienta
                    new Thread(() -> sendVoiceToClient(dataOutputStream)).start();
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }).start();
    }

    // Metoda sendVoiceToClient() wysyła dźwięk do klientów czatu głosowego
    private void sendVoiceToClient(DataOutputStream outputStream) {
        try {
            byte[] buffer = new byte[10000];
            while (true) {
                int count = targetDataLine.read(buffer, 0, buffer.length);
                if (count > 0) {
                    // Rozgłaszanie dźwięku do wszystkich klientów, z wyjątkiem nadawcy
                    for (DataOutputStream dos : voiceClients) {
                        if (!dos.equals(outputStream)) { // Sprawdzanie, czy klient nie jest nadawcą
                            dos.write(buffer, 0, count);
                        }
                    }
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // Metoda sendMessageToClient() wysyła wiadomość tylko do określonego klienta
    private void sendMessageToClient(String message, String senderName, String channelName, Socket clientSocket) {
        try {
            if (!clientSocket.isClosed()) {
                BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(clientSocket.getOutputStream()));
                writer.write(message); // Przekazanie wiadomości do klienta
                writer.newLine();
                writer.flush();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }




    // Metoda sendMessageToAllClients() wysyła wiadomość do wszystkich klientów czatu tekstowego, z wyjątkiem nadawcy
    private synchronized void sendMessageToAllClients(String message, String senderName, String channelName) {
        if (!message.startsWith("Poczekalnia")) {
            for (Map.Entry<String, Socket> entry : clientMap.entrySet()) {
                String clientName = entry.getKey();
                Socket socket = entry.getValue();
                try {
                    if (!socket.isClosed() && !clientName.equals(senderName)) {
                        BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream()));
                        // Formatowanie wiadomości: "nazwa_kanału nazwa_użytkownika: treść_wiadomości"
                        String formattedMessage;
                        if (senderName.equals("Server") || message.startsWith("Changed channel: ")) {
                            // Wiadomość od serwera lub powiadomienie o zmianie kanału
                            formattedMessage = message;
                        } else {
                            // Wiadomość od użytkownika
                            if (channelName != null && !channelName.isEmpty()) {
                                formattedMessage = channelName + " " + senderName + ": " + message;
                            } else {
                                formattedMessage = senderName + ": " + message;
                            }
                        }
                        writer.write(formattedMessage);
                        writer.newLine(); // Dodawanie nowej linii do wiadomości
                        writer.flush();
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
        if (message.startsWith("New channel added: ") || message.startsWith("Channel removed: ")) {
            for (Map.Entry<String, Socket> entry : clientMap.entrySet()) {
                String clientName = entry.getKey();
                Socket socket = entry.getValue();
                try {
                    if (!socket.isClosed()) {
                        BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream()));
                        writer.write(message); // Przekazanie informacji o dodaniu/usunięciu kanału
                        writer.newLine();
                        writer.flush();
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }







    // Metoda addChannel() dodaje nowy kanał do listy kanałów czatu

    private synchronized void addChannel(String channelName) {
        if (!chatRooms.contains(channelName)) {
            chatRooms.add(channelName);
            // Sprawdzamy, czy faktycznie dodano nowy kanał
            if (chatRooms.contains(channelName)) {
                // Wysyłanie pojedynczej wiadomości o utworzeniu kanału do wszystkich klientów
                sendMessageToAllClients("New channel created: " + channelName, "Server", null);
            }
        }
    }



    // Metoda removeChannel() usuwa kanał z listy kanałów czatu
    private void removeChannel(String channelName) {
        chatRooms.remove(channelName);
        // Powiadom klientów o usunięciu kanału
        sendMessageToAllClients("Channel removed: " + channelName, "Server", channelName);
    }

    // Metoda getAudioFormat() zwraca ustawienia audio dla transmisji głosowej
    private AudioFormat getAudioFormat() {
        float sampleRate = 8000.0F;
        int sampleSizeInBits = 16;
        int channels = 1;
        boolean signed = true;
        boolean bigEndian = false;
        return new AudioFormat(sampleRate, sampleSizeInBits, channels, signed, bigEndian);
    }

    // Metoda główna - inicjuje serwer czatu
    public static void main(String[] args) {
        new VoiceChatSerwer();
    }
}
