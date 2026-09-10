/*
 *  Port220 serial backend for DOSBox-X. See port220serial.h for why this
 *  exists rather than using the built-in nullmodem.
 */

#include "dosbox.h"

#include "setup.h"
#include "serialport.h"
#include "port220serial.h"
#include "logging.h"

#include <errno.h>
#include <fcntl.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <arpa/inet.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/types.h>
#include <unistd.h>

/* The core has no Android logging of its own: LOG_MSG goes to stdout, which
 * Android discards. Everything this backend reports would otherwise be
 * invisible -- and "did the backend even run?" is the first question worth
 * being able to answer. liblog is already linked into libretrodos.so. */
#if defined(ANDROID) || defined(__ANDROID__)
#include <android/log.h>
#define P220_LOG(...) __android_log_print(ANDROID_LOG_INFO, "Port220Serial", __VA_ARGS__)
#else
#define P220_LOG(...) LOG_MSG(__VA_ARGS__)
#endif

/* Poll interval in milliseconds of emulated time. 1ms matches what nullmodem
 * uses for its own polling event and is well below one byte time at 10400
 * baud (~0.96ms/byte), so bytes are picked up promptly without spinning. */
#define PORT220_POLL_MS 1.0f

/* Poll ticks between reconnect attempts (~500ms at a 1ms poll). */
#define PORT220_RETRY_TICKS 500

#define PORT220_DEFAULT_HOST "127.0.0.1"
#define PORT220_DEFAULT_PORT 6403

CSerialPort220::CSerialPort220(Bitu id, CommandLine *cmd)
    : CSerial(id, cmd), sock(-1), connected(false),
      retry_host(PORT220_DEFAULT_HOST), retry_port(PORT220_DEFAULT_PORT),
      retry_ticks(0), rx_pacing(false), ctrl_logs(0)
{
    CSerial::Init_Registers();

    /* Logged before anything can fail. If this line is absent from logcat,
     * DOSBox-X never reached this backend at all -- meaning the [serial] type
     * was not recognised, which points at the core patch rather than at
     * anything on the wire. That distinction was previously invisible. */
    P220_LOG("Port220: backend constructing for serial%d", (int)id + 1);

    std::string host = PORT220_DEFAULT_HOST;
    Bitu port = PORT220_DEFAULT_PORT;

    /* getBituSubstring/getStringSubstring are the base class's parsers for
     * "key:value" pairs, the same syntax the other backends use. */
    std::string tmp;
    if (cmd->FindStringBegin("host:", tmp, false) && !tmp.empty())
        host = tmp;
    (void)getBituSubstring("port:", &port, cmd);

    /* Keep the target for later retries. */
    retry_host = host;
    retry_port = (int)port;

    /* A failed first connect is NOT fatal.
     *
     * Previously this set InstallationSuccessful = false and gave up, which
     * made everything depend on the bridge being started before the emulator
     * -- and getting that order wrong produced "Connection refused" and a
     * dead port for the whole session. The bridge may legitimately come up a
     * moment later, so retry from the poll event instead. The port exists
     * either way, so EMSAN1 sees COM1 and can open it. */
    if (!openSocket(host.c_str(), (int)port)) {
        P220_LOG("Port220: bridge not up yet at %s:%d, will keep retrying",
                host.c_str(), (int)port);
    }

    /* The far end is a USB serial adapter, not a modem. Assert the lines the
     * guest looks at so software waiting on CTS/DSR/CD proceeds, and never
     * change them again -- there is no real modem here to reflect. */
    setCTS(true);
    setDSR(true);
    setCD(true);
    setRI(false);

    InstallationSuccessful = true;
    setEvent(SERIAL_PORT220_POLL_EVENT, PORT220_POLL_MS);
}

CSerialPort220::~CSerialPort220()
{
    removeEvent(SERIAL_PORT220_POLL_EVENT);
    removeEvent(SERIAL_PORT220_RX_PACE_EVENT);
    removeEvent(SERIAL_TX_EVENT);
    removeEvent(SERIAL_THR_EVENT);
    closeSocket();
}

bool CSerialPort220::openSocket(const char *host, int port)
{
    sock = ::socket(AF_INET, SOCK_STREAM, 0);
    if (sock < 0) {
        P220_LOG("Port220: socket() failed: %s", strerror(errno));
        return false;
    }

    struct sockaddr_in addr;
    memset(&addr, 0, sizeof(addr));
    addr.sin_family = AF_INET;
    addr.sin_port = htons((uint16_t)port);
    if (inet_pton(AF_INET, host, &addr.sin_addr) != 1) {
        P220_LOG("Port220: bad host address '%s'", host);
        closeSocket();
        return false;
    }

    /* Blocking connect: it is loopback, so it either succeeds at once or the
     * bridge is not listening. A non-blocking connect would only add states
     * to get wrong. */
    if (::connect(sock, (struct sockaddr *)&addr, sizeof(addr)) < 0) {
        P220_LOG("Port220: connect() failed: %s", strerror(errno));
        closeSocket();
        return false;
    }

    /* Nagle would coalesce small writes and add up to 40ms before a byte
     * leaves. On a request/response protocol with a latency budget that is
     * exactly the wrong trade. */
    int one = 1;
    ::setsockopt(sock, IPPROTO_TCP, TCP_NODELAY, (const char *)&one, sizeof(one));

    /* Non-blocking reads: the poll event must never stall the emulator. */
    int flags = ::fcntl(sock, F_GETFL, 0);
    if (flags >= 0) ::fcntl(sock, F_SETFL, flags | O_NONBLOCK);

    connected = true;
    P220_LOG("Port220: connected to %s:%d", host, port);
    return true;
}

void CSerialPort220::closeSocket()
{
    if (sock >= 0) {
        ::close(sock);
        sock = -1;
    }
    connected = false;
}

/* Deliver at most ONE byte per call, then hold off for a byte time.
 *
 * This matches the working Mac configuration. DOSBox-X's nullmodem does not
 * hand the guest everything the socket holds; it delivers a byte, arms an
 * event for ~0.9 x bytetime, and only then delivers the next. Bytes reach
 * the emulated UART at line rate, as they would from a real wire.
 *
 * It matters here more than it would for a terminal program. EMSAN1 talks
 * over a half-duplex line where its own transmissions echo back, and it
 * gates on that echo. Dumping a burst into the UART faster than any physical
 * serial line could carry it changes the inter-byte timing the program was
 * written against. Matching line rate removes that as a variable.
 *
 * Bytes not yet delivered stay in the kernel socket buffer, which is the
 * correct back-pressure: no buffer of our own to overflow. */
void CSerialPort220::pollIncoming()
{
    if (!connected || rx_pacing || !CanReceiveByte()) return;

    uint8_t b;
    const ssize_t n = ::recv(sock, &b, 1, 0);
    if (n == 1) {
        receiveByte(b);
        rx_pacing = true;
        setEvent(SERIAL_PORT220_RX_PACE_EVENT, bytetime * 0.9f);
        return;
    }
    if (n == 0) {
        P220_LOG("Port220: bridge closed the connection");
        closeSocket();
        return;
    }
    if (errno == EAGAIN || errno == EWOULDBLOCK || errno == EINTR) return;
    P220_LOG("Port220: recv() failed: %s", strerror(errno));
    closeSocket();
}

void CSerialPort220::handleUpperEvent(uint16_t type)
{
    switch (type) {
    case SERIAL_PORT220_POLL_EVENT:
        if (connected) {
            pollIncoming();
        } else if (++retry_ticks >= PORT220_RETRY_TICKS) {
            /* Roughly twice a second, not every millisecond: a refused
             * connect is cheap but not free, and hammering it would show up
             * as emulator jitter on a link we care about the timing of. */
            retry_ticks = 0;
            openSocket(retry_host.c_str(), retry_port);
        }
        setEvent(SERIAL_PORT220_POLL_EVENT, PORT220_POLL_MS);
        break;

    case SERIAL_PORT220_RX_PACE_EVENT:
        rx_pacing = false;
        break;

    case SERIAL_THR_EVENT:
        ByteTransmitting();
        setEvent(SERIAL_TX_EVENT, bytetime);
        break;

    case SERIAL_TX_EVENT:
        ByteTransmitted();
        break;

    default:
        break;
    }
}

void CSerialPort220::transmitByte(uint8_t val, bool first)
{
    if (connected) {
        /* Blocking send on a loopback socket to a reader that drains
         * continuously. A short write is possible in principle; loop so a
         * byte is never quietly dropped. */
        size_t off = 0;
        const uint8_t b = val;
        while (off < 1) {
            const ssize_t n = ::send(sock, &b + off, 1 - off, 0);
            if (n > 0) { off += (size_t)n; continue; }
            if (n < 0 && (errno == EAGAIN || errno == EWOULDBLOCK || errno == EINTR))
                continue;
            P220_LOG("Port220: send() failed: %s", strerror(errno));
            closeSocket();
            break;
        }
    }

    /* Timing bookkeeping the UART expects, same as every other backend. */
    if (first) setEvent(SERIAL_THR_EVENT, bytetime / 10);
    else       setEvent(SERIAL_TX_EVENT, bytetime);
}

/* The emulated program setting baud/parity/stop bits does not reach the
 * FT232R: the Kotlin bridge owns the real line settings (10400 8N1). Logged
 * because a mismatch between what EMSAN1 asks for and what the bridge is set
 * to is worth being able to see in a log. */
void CSerialPort220::updatePortConfig(uint16_t divider, uint8_t lcr)
{
    (void)lcr;
    if (divider) {
        const int baud = 115200 / (int)divider;
        P220_LOG("Port220: guest requested %d baud (bridge is fixed at its own rate)", baud);
    }
}

void CSerialPort220::updateMSR()   { /* lines are static; nothing to sample */ }

/* Control lines and break are accepted, logged, and not forwarded.
 *
 * Not forwarded because there is no side channel to the bridge and encoding
 * them as data would corrupt the stream. Logged because what EMSAN1 does
 * with DTR/RTS/break on open is currently unknown, and it decides whether
 * the bridge asserting both lines statically is the right emulation of the
 * Windows build (where directserial passes them through). Bounded so the
 * log shows the open sequence without being flooded during the session. */
#define P220_CTRL_LOG_BUDGET 16

void CSerialPort220::setBreak(bool value)
{
    if (ctrl_logs < P220_CTRL_LOG_BUDGET) {
        ctrl_logs++;
        P220_LOG("Port220: guest set BREAK %s (not forwarded)", value ? "on" : "off");
    }
}

void CSerialPort220::setRTSDTR(bool rts, bool dtr)
{
    if (ctrl_logs < P220_CTRL_LOG_BUDGET) {
        ctrl_logs++;
        P220_LOG("Port220: guest set RTS=%d DTR=%d (bridge holds both high)", rts ? 1 : 0, dtr ? 1 : 0);
    }
}

void CSerialPort220::setRTS(bool val)
{
    if (ctrl_logs < P220_CTRL_LOG_BUDGET) {
        ctrl_logs++;
        P220_LOG("Port220: guest set RTS=%d (bridge holds it high)", val ? 1 : 0);
    }
}

void CSerialPort220::setDTR(bool val)
{
    if (ctrl_logs < P220_CTRL_LOG_BUDGET) {
        ctrl_logs++;
        P220_LOG("Port220: guest set DTR=%d (bridge holds it high)", val ? 1 : 0);
    }
}
