/**
 * Contrats de donnees echanges avec la Gateway.
 *
 * Ils reprennent exactement les entites des microservices (Flight, Traveler,
 * Booking, Conference, Notification) : le frontend ne devine plus la forme des
 * reponses et le compilateur signale les champs renommes cote backend.
 */

export interface Flight {
  id: number;
  flightNumber: string;
  departureCity: string;
  arrivalCity: string;
  departureDate: string;
  availableSeats: number;
  price: number | null;
}

export interface Traveler {
  id: number;
  firstName: string;
  lastName: string;
  email: string;
  role: string;
  favoriteFlights: number[];
}

/** Corps attendu par POST /api/travelers et PUT /api/travelers/{id}. */
export interface TravelerInput {
  firstName: string;
  lastName: string;
  email: string;
  role: string;
}

/** Corps attendu par POST /api/flights (ADMIN). */
export interface FlightInput {
  flightNumber: string;
  departureCity: string;
  arrivalCity: string;
  departureDate: string;
  availableSeats: number;
  price: number | null;
}

export type BookingStatus = 'PENDING' | 'CONFIRMED' | 'CANCELLED';

export interface Booking {
  id: number;
  travelerId: number;
  flightId: number;
  status: BookingStatus;
  totalPrice: number | null;
  createdAt: string;
}

export interface Conference {
  id: number;
  title: string;
  location: string;
  date: string;
  flightId: number | null;
}

export type NotificationType = 'flight_delay' | 'booking_confirmed' | 'payment_success';

export interface Notification {
  id: number;
  traveler_id: number;
  type: NotificationType;
  message: string;
  status: string;
  sentAt: string;
}

export interface NotificationPayload {
  traveler_id: number;
  type: NotificationType;
  message: string;
}

/**
 * Entree archivee par le consommateur RabbitMQ (packh_rabbit_consumer.py).
 * Exposee par GET /api/notifications/events.
 */
export interface ConsumedEvent {
  routingKey: string;
  receivedAt: string;
  payload: Record<string, unknown>;
}

/** Reponse d'un endpoint d'emission RabbitMQ (/api/packh/**). */
export interface EmittedEvent {
  type: string;
  routingKey: string;
  publishedTo: string;
  occurredAt: string;
  [key: string]: unknown;
}
