package space.pxls;

import com.google.common.collect.Lists;
import com.google.gson.*;
import com.typesafe.config.Config;
import org.jooby.*;
import org.jooby.json.Gzon;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

public class App extends Jooby {
    private int width;
    private int height;
    private byte[] board;
    private List<String> palette = Lists.newArrayList("#FFFFFF", "#E4E4E4", "#888888", "#222222", "#FFA7D1", "#E50000", "#E59500", "#A06A42", "#E5D900", "#94E044", "#02BE01", "#00D3DD", "#0083C7", "#0000EA", "#CF6EE4", "#820080");
    private Set<WebSocket> sockets = new HashSet<>();
    private Map<String, Long> lastPlaceTime = new HashMap<>();

    private Logger placementLog = LoggerFactory.getLogger("PIXELS");
    private int cooldownSeconds = 0;

    {
        onStart(() -> {
            Config cfg = require(Config.class);
            cooldownSeconds = cfg.hasPath("game.cooldown") ? cfg.getInt("game.cooldown") : 10;

            width = cfg.getInt("game.width");
            height = cfg.getInt("game.height");
            board = new byte[width * height];

            try {
                loadBoard();
            } catch (IOException e) {
                e.printStackTrace();
            }
        });

        renderer((value, ctx) -> {
            if (value instanceof byte[]) {
                ctx.send((byte[]) value);
            }
        });

        use(new Gzon().doWith((gson) -> gson.registerTypeAdapter(byte[].class, new ByteArrayToBase64TypeAdapter())));

        assets("/", "index.html");
        assets("/assets/**");

        ws("/ws", (req, sock) -> {
            sockets.add(sock);
            sock.onClose((status) -> {
                sockets.remove(sock);
            });
        }).produces(MediaType.json);

        get("/boardinfo", (req, resp) -> {
            resp.send(new BoardDataResponse(width, height, palette));
        });

        get("/boarddata", (req, resp) -> {
            resp.send(board);
        });

        get("/cooldown", (req, resp) -> {
            long lastPlace = lastPlaceTime.getOrDefault(getIp(req), 0L);
            long nextPlace = lastPlace + cooldownSeconds * 1000;
            long waitMillis = nextPlace - System.currentTimeMillis();

            resp.send(Math.max(0, waitMillis / 1000f));
        });

        post("/place", (req, resp) -> {
            int x = req.param("x").intValue();
            int y = req.param("y").intValue();
            int color = req.param("color").intValue();

            long lastPlace = lastPlaceTime.getOrDefault(getIp(req), 0L);
            long nextPlace = lastPlace + cooldownSeconds * 1000;
            long waitMillis = nextPlace - System.currentTimeMillis();

            if (x < 0 || y < 0 || x >= width || y >= height) {
                resp.status(Status.BAD_REQUEST).send(new Error("Invalid coordinates"));
                return;
            } else if (color < 0 || color >= palette.size()) {
                resp.status(Status.BAD_REQUEST).send(new Error("Invalid color"));
                return;
            } else if (waitMillis > 0) {
                resp.status(Status.I_AM_A_TEAPOT).send(new WaitError("pls no", waitMillis / 1000f));
                return;
            }

            board[coordsToIndex(x, y)] = (byte) color;
            placementLog.info(x + "," + y + "," + color + " by " + getIp(req));

            saveBoard();

            lastPlaceTime.put(getIp(req), System.currentTimeMillis());

            resp.status(Status.OK).send(new BoardPlaceResponse(cooldownSeconds));

            for (WebSocket socket : sockets) {
                if (socket.isOpen()) {
                    socket.send(new BoardUpdate(x, y, color));
                }
            }
        });

        on("prod", () -> {
            err((req, resp, err) -> {
                resp.send(new Error(err.getCause().getMessage()));
            });
        });

        use("*", "/admin/*", (req, rsp, chain) -> {
            Config cfg = require(Config.class);

            boolean proceed = true;
            if (!cfg.hasPath("application.adminToken")) {
                rsp.status(Status.NOT_FOUND);
                proceed = false;
            } else if (!req.param("token").isSet()) {
                rsp.status(Status.FORBIDDEN);
                proceed = false;
            } else if (!cfg.getString("application.adminToken").equals(req.param("token").value())) {
                rsp.status(Status.FORBIDDEN);
                proceed = false;
            }

            if (proceed) {
                chain.next(req, rsp);
            }
        });

        get("/admin/changeCooldown", (req, resp) -> {
            cooldownSeconds = req.param("cooldown").intValue();
            resp.send("Cooldown changed to " + cooldownSeconds);
        }).produces(MediaType.plain);

        get("/admin/alert", (req, resp) -> {
            String msg = req.param("message").value();
            resp.send("Alerted " + msg);

            for (WebSocket socket : sockets) {
                socket.send(new Alert(msg));
            }
        }).produces(MediaType.plain);


        get("/wp-admin/install.php", (req, resp) -> {
            resp.redirect("https://www.youtube.com/watch?v=dQw4w9WgXcQ");
        });
    }

    private String getIp(Request r) {
        if (r.header("X-Forwarded-For").isSet()) return r.header("X-Forwarded-For").value();
        return r.ip();
    }

    private void loadBoard() throws IOException {
        String path = require(Config.class).getString("game.file");
        byte[] bytes = Files.readAllBytes(Paths.get(path));
        System.arraycopy(bytes, 0, board, 0, Math.min(bytes.length, board.length));
    }

    private void saveBoard() throws IOException {
        String path = require(Config.class).getString("game.file");
        Files.write(Paths.get(path), board);
    }

    private int coordsToIndex(int x, int y) {
        return y * width + x;
    }

    public static void main(final String[] args) {
        run(App::new, args);
    }

    public class BoardDataResponse {
        int width;
        int height;
        List<String> palette;

        public BoardDataResponse(int width, int height, List<String> palette) {
            this.width = width;
            this.height = height;
            this.palette = palette;
        }
    }

    public class Error {
        String error;

        public Error(String error) {
            this.error = error;
        }
    }

    public class WaitError {
        String error;
        float wait;

        public WaitError(String error, float wait) {
            this.error = error;
            this.wait = wait;
        }
    }

    public class BoardPlaceResponse {
        float wait;

        public BoardPlaceResponse(float wait) {
            this.wait = wait;
        }
    }

    public class BoardUpdate {
        String type = "pixel";
        int x;
        int y;
        int color;

        public BoardUpdate(int x, int y, int color) {
            this.x = x;
            this.y = y;
            this.color = color;
        }
    }

    public class Alert {
        String type = "alert";
        String message;

        public Alert(String message) {
            this.message = message;
        }
    }

    private static class ByteArrayToBase64TypeAdapter implements JsonSerializer<byte[]>, JsonDeserializer<byte[]> {
        public byte[] deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) throws JsonParseException {
            return Base64.getDecoder().decode(json.getAsString());
        }

        public JsonElement serialize(byte[] src, Type typeOfSrc, JsonSerializationContext context) {
            return new JsonPrimitive(Base64.getEncoder().encodeToString(src));
        }
    }
}